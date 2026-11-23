package nzi.fhir.validator.core.throttle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 014 convergence: the count-sharing tick touches only the windows that changed (T054, Constitution I), and a
 * full window cache stays within SC-008 (about 60 MB at 100 000 subjects, T055).
 */
class ThrottleFootprintTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final int SUBJECTS = 100_000;

    /** T054: with 100 000 tracked windows and 5 changed, one tick drains exactly those 5. */
    @Test
    void aTickDrainsOnlyTheChangedWindows() {
        Throttle t = new Throttle(200_000, 1_000);
        for (int i = 0; i < SUBJECTS; i++) {
            t.window(Throttle.KEY, UUID.randomUUID()).setRemote(NOW / 1000, 1, NOW / 1000);
        }
        Map<UUID, Integer> caps = new HashMap<>();
        UUID[] keys = new UUID[5];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = UUID.randomUUID();
            caps.put(keys[i], 10);
        }
        ThrottleCaps c = new ThrottleCaps(caps, Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
        for (UUID k : keys) {
            assertTrue(t.check(k, UUID.randomUUID(), c, NOW).admitted());
        }
        ThrottleSimulationTest.MemoryStore store = new ThrottleSimulationTest.MemoryStore();
        ThrottleSync sync = new ThrottleSync(t, store, 1_000, () -> NOW, "test");
        sync.tick();
        assertEquals(5, t.lastDrained(), "only the changed windows are visited");
        assertEquals(5, store.counts.size(), "and flushed");
        sync.tick();
        assertEquals(0, t.lastDrained(), "nothing changed since");
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * T055 (SC-008): the worst case, every subject with this instance's and the peers' counts, costs at most 600 bytes
     * per subject, cache entry included. Measured from the heap; the smallest of three runs is used.
     */
    @Test
    void aFullCacheStaysWithinTheMemoryBudget() {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long before = usedHeap();
            Throttle t = new Throttle(SUBJECTS, 1_000);
            long now = NOW / 1000;
            for (int i = 0; i < SUBJECTS; i++) {
                Window w = t.window(i % 2 == 0 ? Throttle.KEY : Throttle.ORG, UUID.randomUUID());
                w.addLocal(now);
                w.setRemote(now, 3, now);
            }
            long after = usedHeap();
            assertEquals(SUBJECTS, t.trackedSubjects());
            best = Math.min(best, (after - before) / SUBJECTS);
        }
        System.out.println("throttle footprint: " + best + " bytes per tracked subject (budget 600)");
        assertTrue(best <= 600, "bytes per tracked subject: " + best);
    }
}
