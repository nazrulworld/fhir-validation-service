package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 014: regression tests for the findings of the code review of #99. */
class ThrottleReviewTest {
    private static final long NOW = 1_800_000_000_000L;

    /** A store that fails a flush writing the same (scope, subject, second) twice, as PostgreSQL's upsert does. */
    static final class StrictStore extends ThrottleSimulationTest.MemoryStore {
        @Override
        public Future<Void> flush(UUID instance, List<CountRow> rows) {
            Set<List<Object>> seen = new HashSet<>();
            for (CountRow r : rows) {
                if (!seen.add(List.of(r.scope(), r.subject(), r.second()))) {
                    return Future.failedFuture(new IllegalStateException(
                            "ON CONFLICT DO UPDATE command cannot affect row a second time"));
                }
            }
            return super.flush(instance, rows);
        }
    }

    private static ThrottleCaps keyCaps(UUID key, int cap) {
        return new ThrottleCaps(Map.of(key, cap), Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
    }

    /** Finding 1: a window evicted and recreated before a flush is written once, from the cache's current window. */
    @Test
    void anEvictedAndRecreatedWindowIsFlushedOnce() {
        Throttle t = new Throttle(1_000, 1_000);
        UUID key = UUID.randomUUID();
        ThrottleCaps caps = keyCaps(key, 100);
        assertTrue(t.check(key, UUID.randomUUID(), caps, NOW).admitted());
        t.evict(new Throttle.SubjectKey(Throttle.KEY, key));
        assertTrue(t.check(key, UUID.randomUUID(), caps, NOW).admitted());
        StrictStore store = new StrictStore();
        ThrottleSync sync = new ThrottleSync(t, store, 1_000, () -> NOW, "a");
        sync.start();
        sync.tick();
        assertFalse(sync.localMode(), "the flush succeeded");
        assertEquals(1, store.counts.size(), "one row for the subject and second");
    }

    /** Finding 2: an admission stays unconfirmed until a drain has moved it into a flushed generation. */
    @Test
    void pendingAdmissionsAreConfirmedOnlyAfterTheirFlush() {
        Window w = new Window();
        long now = NOW / 1000;
        w.addLocal(now);
        w.addPending();
        assertEquals(1, w.unconfirmed(Long.MAX_VALUE), "unflushed: unconfirmed whatever is confirmed");
        w.drainDirty(now, 5, 4);
        assertEquals(1, w.unconfirmed(4), "flushed in generation 5, not confirmed yet");
        assertEquals(0, w.unconfirmed(5), "confirmed with generation 5");
        w.addPending();
        assertEquals(1, w.unconfirmed(5), "an admission after the drain waits for the next one");
        // Generations 5 and 7 share a bucket; 5 isn't confirmed (failed flushes), so it is merged, never dropped.
        w.drainDirty(now, 7, 4);
        assertEquals(2, w.unconfirmed(4));
        assertEquals(0, w.unconfirmed(7));
    }

    /**
     * Finding 2, concurrently: admissions on many threads while a sync ticks. Every admission reaches the store, and
     * none is counted as confirmed before the flush that wrote it.
     */
    @Test
    @Timeout(30)
    void concurrentAdmissionsAreNeverLostOrConfirmedEarly() throws InterruptedException {
        Throttle t = new Throttle(10_000, 1_000);
        UUID[] keys = new UUID[20];
        Map<UUID, Integer> capMap = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            keys[i] = UUID.randomUUID();
            capMap.put(keys[i], 1_000_000);
        }
        ThrottleCaps caps = new ThrottleCaps(capMap, Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
        StrictStore store = new StrictStore();
        ThrottleSync sync = new ThrottleSync(t, store, 1_000, () -> NOW, "a");
        sync.start();
        Map<UUID, AtomicInteger> admitted = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            int seed = n;
            Thread th = new Thread(() -> {
                int i = seed;
                while (!stop.get()) {
                    UUID k = keys[i++ % keys.length];
                    if (t.check(k, UUID.randomUUID(), caps, NOW).admitted()) {
                        admitted.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
                    }
                }
            });
            th.start();
            threads.add(th);
        }
        // The ticks must overlap the admissions: wait until every key has been admitted (all threads running), then
        // tick for at least 300 ms as well as 300 times (300 ticks alone can finish before the threads get going).
        long deadline = System.currentTimeMillis() + 10_000;
        while (admitted.size() < keys.length) {
            assertTrue(System.currentTimeMillis() < deadline, "admitting threads didn't start");
            Thread.onSpinWait();
        }
        long minUntil = System.currentTimeMillis() + 300;
        for (int i = 0; i < 300 || System.currentTimeMillis() < minUntil; i++) {
            sync.tick();
            // Confirmed admissions must already be in the store: unconfirmed ≥ admitted − flushed, per key.
            long confirmed = t.confirmedThrough();
            for (UUID k : keys) {
                Window w = t.existingWindow(Throttle.KEY, k);
                if (w == null) {
                    continue;
                }
                int stored = store.counts.getOrDefault(List.of(Throttle.KEY, k, NOW / 1000, sync.instanceId()), 0);
                int local = w.localCount(NOW / 1000);
                assertTrue(w.unconfirmed(confirmed) >= local - stored,
                        "admissions not yet stored must be unconfirmed: local " + local + ", stored " + stored);
            }
        }
        stop.set(true);
        for (Thread th : threads) {
            th.join();
        }
        sync.tick();
        sync.tick();
        for (UUID k : keys) {
            int expected = admitted.getOrDefault(k, new AtomicInteger()).get();
            assertEquals(expected, (int) store.counts.getOrDefault(List.of(Throttle.KEY, k, NOW / 1000, sync.instanceId()), 0),
                    "every admission is flushed");
            Window w = t.existingWindow(Throttle.KEY, k);
            // No window: the key was never admitted (expected is 0 and checked above), so there is nothing to confirm.
            assertEquals(0, w == null ? 0 : w.unconfirmed(t.confirmedThrough()), "and confirmed");
        }
    }

    /** Finding 4: the set of changed subjects never outgrows FV_THROTTLE_MAX_TRACKED; past it, one full scan. */
    @Test
    void theChangedSetIsBounded() {
        Throttle t = new Throttle(1_000, 1_000);
        Map<UUID, Integer> capMap = new HashMap<>();
        UUID[] keys = new UUID[3_000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = UUID.randomUUID();
            capMap.put(keys[i], 10);
        }
        ThrottleCaps caps = new ThrottleCaps(capMap, Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
        for (UUID k : keys) {
            t.check(k, null, caps, NOW);
        }
        assertTrue(t.changedSize() <= 1_000, "changed set: " + t.changedSize());
        ThrottleSync sync = new ThrottleSync(t, new StrictStore(), 1_000, () -> NOW, "a");
        sync.start();
        sync.tick();
        assertTrue(t.lastDrained() <= 1_000 + 50, "one scan of the tracked windows: " + t.lastDrained());
        assertEquals(0, t.changedSize());
        sync.tick();
        assertEquals(0, t.lastDrained(), "back to the changed set");
    }

    /** A store whose flushes never complete: the database stopped answering. */
    static final class HangingStore extends ThrottleSimulationTest.MemoryStore {
        @Override
        public Future<Void> flush(UUID instance, List<CountRow> rows) {
            return Promise.<Void>promise().future();
        }
    }

    /** Finding 3: a first tick that never completes can't hold warm-up (and so the start) for ever: local mode. */
    @Test
    void aHungFirstTickStillEndsTheWarmUp() {
        long[] clock = {NOW};
        HangingStore store = new HangingStore();
        Throttle t = new Throttle(1_000, 1_000);
        ThrottleSync sync = new ThrottleSync(t, store, 1_000, () -> clock[0], "a");
        sync.start();
        sync.tick(); // hangs
        assertFalse(sync.warm());
        for (int i = 1; i <= 5 && !sync.warm(); i++) {
            clock[0] = NOW + i * 1_000L;
            sync.tick(); // skipped: the first is still running
        }
        assertTrue(sync.localMode(), "no successful read for 3 intervals");
        assertTrue(sync.warm());
        assertTrue(sync.warmed().succeeded());
    }

    /** Finding 3: a stop during warm-up releases whoever waits for it. */
    @Test
    void aStopDuringWarmUpReleasesTheWaiters() {
        ThrottleSync sync = new ThrottleSync(new Throttle(1_000, 1_000), new StrictStore(), 1_000, () -> NOW, "a");
        sync.start();
        assertFalse(sync.warmed().isComplete());
        sync.stop();
        assertTrue(sync.warmed().succeeded());
    }
}
