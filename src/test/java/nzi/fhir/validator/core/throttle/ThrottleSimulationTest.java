package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 014 (T039, SC-001, research R4): a deterministic fleet of 1–3 instances, each a real {@link Throttle} and
 * {@link ThrottleSync}, sharing counts through an in-memory {@link CountStore}. Instances tick once a second at different
 * phases, so each sees the others' admissions only after their next flush and its own next read, as in production.
 * Asserted: in every rolling 60 s the fleet admits at most the cap on one instance and at most cap + max(1, ⌈cap/10⌉)
 * on 2–3 instances; and, when the load exceeds the cap, the full cap is admitted for caps of at least 10 per peer.
 */
class ThrottleSimulationTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final long DURATION_MS = 5 * 60_000;
    private static final long STEP_MS = 10;
    private static final long INTERVAL_MS = 1_000;
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    enum Balancing { STICKY, ROUND_ROBIN, RANDOM }

    enum Load { STEADY, BURSTS }

    static Stream<Arguments> fleets() {
        List<Arguments> out = new ArrayList<>();
        for (int instances = 1; instances <= 3; instances++) {
            for (int cap : new int[]{1, 5, 10, 30, 60, 600, 1200}) {
                for (Balancing b : Balancing.values()) {
                    for (Load l : Load.values()) {
                        out.add(Arguments.of(instances, cap, b, l));
                    }
                }
            }
        }
        return out.stream();
    }

    /** The shared table: absolute counts per (scope, subject, second, instance), and heartbeats. */
    static final class MemoryStore implements CountStore {
        final Map<List<Object>, Integer> counts = new HashMap<>();
        final Map<UUID, Long> seen = new HashMap<>();
        long now;

        @Override
        public Future<Void> flush(UUID instance, List<CountRow> rows) {
            rows.forEach(r -> counts.put(List.of(r.scope(), r.subject(), r.second(), instance), r.count()));
            return Future.succeededFuture();
        }

        @Override
        public Future<Integer> heartbeat(UUID instance, String name) {
            seen.put(instance, now);
            return Future.succeededFuture((int) seen.entrySet().stream()
                    .filter(e -> !e.getKey().equals(instance) && e.getValue() > now - 5_000).count());
        }

        @Override
        public Future<List<CountRow>> read(UUID instance, long sinceSecond) {
            Map<List<Object>, Integer> sums = new HashMap<>();
            counts.forEach((k, v) -> {
                if (!k.get(3).equals(instance) && (Long) k.get(2) >= sinceSecond) {
                    sums.merge(List.of(k.get(0), k.get(1), k.get(2)), v, Integer::sum);
                }
            });
            List<CountRow> out = new ArrayList<>();
            sums.forEach((k, v) -> out.add(new CountRow((Character) k.get(0), (UUID) k.get(1), (Long) k.get(2), v)));
            return Future.succeededFuture(out);
        }

        @Override
        public Future<Void> cleanup(long beforeSecond) {
            counts.keySet().removeIf(k -> (Long) k.get(2) < beforeSecond);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> remove(UUID instance) {
            seen.remove(instance);
            return Future.succeededFuture();
        }
    }

    @ParameterizedTest(name = "{0} instance(s), cap {1}, {2}, {3}")
    @MethodSource("fleets")
    void fleetStaysWithinTheBound(int instances, int cap, Balancing balancing, Load load) {
        MemoryStore store = new MemoryStore();
        long[] clock = {T0};
        Throttle[] throttles = new Throttle[instances];
        ThrottleSync[] syncs = new ThrottleSync[instances];
        for (int i = 0; i < instances; i++) {
            throttles[i] = new Throttle(1_000, INTERVAL_MS);
            syncs[i] = new ThrottleSync(throttles[i], store, INTERVAL_MS, () -> clock[0], "sim-" + i);
        }
        // T056: with 2–3 instances the last one joins after two minutes, while the others are under load.
        long joinAt = instances > 1 ? T0 + 120_000 : T0;
        boolean[] started = new boolean[instances];
        for (int i = 0; i < instances; i++) {
            if (i < instances - 1 || joinAt == T0) {
                store.now = clock[0];
                syncs[i].start();
                started[i] = true;
            }
        }
        ThrottleCaps caps = new ThrottleCaps(Map.of(KEY, cap), Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
        Random random = new Random(cap * 31L + instances * 7L + balancing.ordinal() * 3L + load.ordinal());
        // Offered load: twice the cap per minute (at least 1/s); bursts: the whole cap at once every 20 s, then steady.
        double perStep = Math.max(1.0, 2.0 * cap / 60.0) * STEP_MS / 1000.0;
        double owed = 0;
        int next = 0;
        List<Long> admitted = new ArrayList<>();
        for (long t = T0; t < T0 + DURATION_MS; t += STEP_MS) {
            clock[0] = t;
            store.now = t;
            if (!started[instances - 1] && t >= joinAt) {
                syncs[instances - 1].start();
                started[instances - 1] = true;
            }
            for (int i = 0; i < instances; i++) {
                // Phases on the simulation's 10 ms grid (333 ms would never be hit).
                long phase = i * INTERVAL_MS / instances / STEP_MS * STEP_MS;
                if (started[i] && Math.floorMod(t - T0 - phase, INTERVAL_MS) == 0) {
                    syncs[i].tick();
                }
            }
            // The load balancer sends requests only to ready instances (T056: not before two sync ticks).
            List<Integer> ready = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                if (started[i] && syncs[i].warm()) {
                    ready.add(i);
                }
            }
            int requests;
            owed += perStep;
            requests = (int) owed;
            owed -= requests;
            if (load == Load.BURSTS && Math.floorMod(t - T0, 20_000) == 0) {
                requests += cap * 2;
            }
            for (int r = 0; r < requests && !ready.isEmpty(); r++) {
                int target = switch (balancing) {
                    // Sticky: the client is pinned to the first instance; after the join, half of the time to the new one.
                    case STICKY -> ready.contains(instances - 1) && instances > 1 && (t / 30_000) % 2 == 1 ? instances - 1 : 0;
                    case ROUND_ROBIN -> ready.get(next++ % ready.size());
                    case RANDOM -> ready.get(random.nextInt(ready.size()));
                };
                if (!ready.contains(target)) {
                    continue;
                }
                if (throttles[target].check(KEY, ORG, caps, t).admitted()) {
                    admitted.add(t);
                }
            }
        }
        int peers = instances - 1;
        int b = Math.max(1, (cap + 9) / 10);
        // A cap smaller than the number of instances (cap 1 on 3) can be exceeded by up to the number of peers: research R4.
        int bound = instances == 1 ? cap : cap < instances ? cap + peers : cap + b;
        int max = 0;
        int lo = 0;
        for (int hi = 0; hi < admitted.size(); hi++) {
            while (admitted.get(hi) - admitted.get(lo) >= 60_000) {
                lo++;
            }
            max = Math.max(max, hi - lo + 1);
        }
        assertTrue(max <= bound, "admitted " + max + " in a rolling minute; bound " + bound);
        if (peers == 0 || cap >= 10 * peers) {
            assertTrue(max >= (peers == 0 ? cap : cap - peers), "the cap is used: " + max + " of " + cap);
        }
    }

    /**
     * T056: an instance joins while the window has room. Before the old instances' next tick they still reserve room for
     * one peer, not two; the newcomer must not be sent requests until they count it (it is ready only after two ticks).
     * Without that, cap 30 (bound 33) admits 34 here.
     */
    @org.junit.jupiter.api.Test
    void aJoiningInstanceDoesNotBreakTheBound() {
        MemoryStore store = new MemoryStore();
        long[] clock = {T0};
        Throttle a = new Throttle(1_000, INTERVAL_MS);
        Throttle b = new Throttle(1_000, INTERVAL_MS);
        Throttle c = new Throttle(1_000, INTERVAL_MS);
        ThrottleSync sa = new ThrottleSync(a, store, INTERVAL_MS, () -> clock[0], "a");
        ThrottleSync sb = new ThrottleSync(b, store, INTERVAL_MS, () -> clock[0], "b");
        ThrottleSync sc = new ThrottleSync(c, store, INTERVAL_MS, () -> clock[0], "c");
        ThrottleCaps caps = new ThrottleCaps(Map.of(KEY, 30), Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
        store.now = T0;
        sa.start();
        sb.start();
        int admitted = 0;
        // 27 admissions on a (paced by the burst guard), both instances ticking once a second.
        for (long t = T0; t < T0 + 30_000; t += 100) {
            clock[0] = t;
            store.now = t;
            if ((t - T0) % 1_000 == 0) {
                sa.tick();
                sb.tick();
            }
            if ((t - T0) % 200 == 0 && admitted < 27 && a.check(KEY, ORG, caps, t).admitted()) {
                admitted++;
            }
        }
        assertTrue(admitted == 27, "setup: " + admitted);
        // c joins between the old instances' ticks; then 10 requests to each instance that is ready.
        long t = T0 + 30_050;
        clock[0] = t;
        store.now = t;
        sc.start();
        for (Object[] inst : new Object[][]{{a, sa}, {b, sb}, {c, sc}}) {
            if (!((ThrottleSync) inst[1]).warm()) {
                continue;
            }
            for (int r = 0; r < 10; r++) {
                if (((Throttle) inst[0]).check(KEY, ORG, caps, t).admitted()) {
                    admitted++;
                }
            }
        }
        assertTrue(admitted <= 33, "fleet admitted " + admitted + " in the minute; bound 33");
    }
}
