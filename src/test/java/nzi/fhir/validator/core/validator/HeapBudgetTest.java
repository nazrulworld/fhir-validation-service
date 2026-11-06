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
    void reserveEvictsUntilTheBuildFits() {
        AtomicLong used = new AtomicLong(1400 * MB);
        AtomicInteger evictions = new AtomicInteger();
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, used::get, () -> { });
        // Each eviction frees 200 MB; a 50 MB build needs about 364 MB and 136 MB are free.
        long reserved = budget.reserve(50 * MB, () -> {
            evictions.incrementAndGet();
            used.addAndGet(-200 * MB);
            return true;
        });
        assertEquals(2, evictions.get());
        assertEquals(HeapBudget.estimate(50 * MB), reserved);
    }

    @Test
    void nothingLeftToEvictIsInsufficientCapacity() {
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, () -> 1400 * MB, () -> { });
        AtomicInteger asked = new AtomicInteger();
        HeapBudget.InsufficientCapacityException e = assertThrows(HeapBudget.InsufficientCapacityException.class,
                () -> budget.reserve(50 * MB, () -> asked.incrementAndGet() < 0));
        assertEquals(1, asked.get());
        assertTrue(e.getMessage().contains("MB of heap"), e.getMessage());
    }

    @Test
    void largerThanTheWholeHeapNeverFitsAndEvictsNothing() {
        HeapBudget budget = new HeapBudget(() -> 1024 * MB, () -> 100 * MB, () -> { });
        AtomicInteger asked = new AtomicInteger();
        assertThrows(HeapBudget.NeverFitsException.class, () -> budget.reserve(200 * MB, () -> asked.incrementAndGet() > 0));
        assertEquals(0, asked.get(), "no context is dropped for a build that can never fit");
    }

    @Test
    void includeAdmissionEvictsOnDemandContextsFirst() {
        AtomicLong used = new AtomicLong(1400 * MB);
        HeapBudget budget = new HeapBudget(() -> 1536 * MB, used::get, () -> { });
        budget.admitInclude(50 * MB, () -> {
            used.set(500 * MB);
            return true;
        });
        ProblemException e = assertThrows(ProblemException.class, () -> new HeapBudget(() -> 1536 * MB, () -> 1400 * MB, () -> { })
                .admitInclude(50 * MB, HeapBudget.Evictor.NONE));
        assertEquals(413, e.status());
    }
}
