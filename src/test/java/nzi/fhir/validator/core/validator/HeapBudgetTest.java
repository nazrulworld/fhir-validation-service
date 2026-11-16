package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HeapBudgetTest {
    private static final long MB = 1024L * 1024L;

    @Test
    void aBuildThatFitsIsAdmittedWithoutCollecting() {
        AtomicInteger collections = new AtomicInteger();
        HeapBudget budget = new HeapBudget(() -> 2048 * MB, () -> 900 * MB, collections::incrementAndGet);
        budget.check(20 * MB);
        assertEquals(0, collections.get());
    }

    @Test
    void aBuildThatDoesNotFitIsRefusedAfterACollection() {
        AtomicInteger collections = new AtomicInteger();
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, () -> 874 * MB, collections::incrementAndGet);
        // The 20 MB examples IG: 180 MB expanded, about 1.1 GB estimated, 0.65 GB free.
        HeapBudget.InsufficientHeapException e = assertThrows(HeapBudget.InsufficientHeapException.class,
                () -> budget.check(180 * MB));
        assertEquals(1, collections.get());
        assertTrue(e.getMessage().contains("FV_MEMORY_LIMIT"), e.getMessage());
        assertTrue(e.getMessage().contains("MB of heap"), e.getMessage());
    }

    @Test
    void garbageFreedByTheCollectionCounts() {
        AtomicLong used = new AtomicLong(1400 * MB);
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, used::get, () -> used.set(400 * MB));
        budget.check(100 * MB);
    }

    @Test
    void reservedBuildsCountAgainstLaterOnes() {
        HeapBudget budget = new HeapBudget(() -> 2048 * MB, () -> 900 * MB, () -> { });
        long first = budget.reserve(100 * MB);
        assertThrows(HeapBudget.InsufficientHeapException.class, () -> budget.check(100 * MB));
        budget.release(first);
        budget.check(100 * MB);
    }

    @Test
    void anIncludeThatDoesNotFitIs413() {
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, () -> 874 * MB, () -> { });
        ProblemException e = assertThrows(ProblemException.class, () -> budget.admitInclude(180 * MB));
        assertEquals(413, e.status());
        assertEquals("package-too-large", e.type());
    }

    @Test
    void aBuildThatDoesNotFitNextToWhatIsLiveIsRefused() {
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, () -> 1400 * MB, () -> { });
        HeapBudget.InsufficientHeapException e = assertThrows(HeapBudget.InsufficientHeapException.class,
                () -> budget.reserve(50 * MB));
        assertFalse(e instanceof HeapBudget.NeverFitsException);
        assertTrue(e.getMessage().contains("MB of heap"), e.getMessage());
    }

    @Test
    void largerThanTheWholeHeapNeverFits() {
        HeapBudget budget = new HeapBudget(() -> 1024 * MB, () -> 100 * MB, () -> { });
        assertThrows(HeapBudget.NeverFitsException.class, () -> budget.reserve(200 * MB));
    }

    /** Spec 007: nothing is evictable any more, so an include that doesn't fit is 413. */
    @Test
    void includeAdmissionRefusesWhatDoesNotFit() {
        new HeapBudget(() -> 1536 * MB, () -> 500 * MB, () -> { }).admitInclude(50 * MB);
        ProblemException e = assertThrows(ProblemException.class, () -> new HeapBudget(() -> 1536 * MB, () -> 1400 * MB, () -> { })
                .admitInclude(50 * MB));
        assertEquals(413, e.status());
    }

    /** Spec 007 SC-009: a terminology context's code cache adds cacheSize × 1 KB (about 5 MB at the default 5000). */
    @Test
    void theCodeCacheIsReservedOnceForTheInstance() {
        // Spec 010 FR-024: one fixed reservation (entries x 1 KB), not one per build.
        HeapBudget budget = new HeapBudget(() -> 4096 * MB, () -> 0, () -> { });
        long fixed = 50_000L * nzi.fhir.validator.core.terminology.TerminologyCodeCache.ENTRY_BYTES;
        budget.reserveFixed(fixed);
        assertEquals(HeapBudget.estimate(10 * MB), budget.reserve(10 * MB), "a build reserves its packages only");
        HeapBudget tight = new HeapBudget(() -> HeapBudget.estimate(10 * MB) + fixed - 1, () -> 0, () -> { });
        tight.reserveFixed(fixed);
        assertThrows(HeapBudget.InsufficientHeapException.class, () -> tight.reserve(10 * MB));
    }

    /** Spec 007 SC-009 (T080): the include check counts the code cache bound too, so a build can't fail later. */
    @Test
    void theIncludeCheckCountsTheCodeCacheBound() {
        // About 128 MB free: the package estimate fits, the package plus a 100 MB code cache bound doesn't.
        long freeAfterEstimate = HeapBudget.estimate(10 * MB) + 28 * MB;
        HeapBudget budget = new HeapBudget(() -> 4096 * MB, () -> 4096 * MB - freeAfterEstimate, () -> { });
        budget.admitInclude(10 * MB, 0);
        budget.admitInclude(10 * MB, 20 * MB);
        ProblemException e = assertThrows(ProblemException.class, () -> budget.admitInclude(10 * MB, 100 * MB));
        assertEquals(413, e.status());
    }

    /** Spec 010 FR-024: a package already shared costs a build its indexes only (1 byte per unpacked byte). */
    @Test
    void sharedPackagesCostTheirIndexesOnly() {
        assertEquals(40 * MB, HeapBudget.sharedBytes(40 * MB));
        assertEquals(0, HeapBudget.sharedBytes(-1));
        HeapBudget budget = new HeapBudget(() -> 4096 * MB, () -> 0, () -> { });
        long reserved = budget.reserve(10 * MB, HeapBudget.sharedBytes(100 * MB));
        assertEquals(HeapBudget.estimate(10 * MB) + 100 * MB, reserved);
        assertEquals(reserved, budget.reservedBytes(), "held until released");
        budget.release(reserved);
        assertEquals(0, budget.reservedBytes());
        assertTrue(HeapBudget.estimate(110 * MB) > HeapBudget.estimate(10 * MB) + HeapBudget.sharedBytes(100 * MB),
                "far less than a full reparse");
    }

    /** Spec 010 edge case: when memory looks short, a full collection runs first, so released entries count as free. */
    @Test
    void aCollectionRunsBeforeABuildIsRefused() {
        long[] used = {4000 * MB};
        int[] collections = {0};
        HeapBudget budget = new HeapBudget(() -> 4096 * MB, () -> used[0], () -> {
            collections[0]++;
            used[0] = 100 * MB; // the collection freed definitions no context uses any more
        });
        assertDoesNotThrow(() -> budget.reserve(10 * MB, HeapBudget.sharedBytes(20 * MB)));
        assertEquals(1, collections[0]);
    }
}
