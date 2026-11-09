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
    void aTerminologyContextAddsItsCodeCacheBound() {
        var none = nzi.fhir.validator.core.terminology.TerminologySettings.NONE;
        var server = new nzi.fhir.validator.core.terminology.TerminologySettings(
                nzi.fhir.validator.core.terminology.TerminologyMode.SERVER, "http://tx.fhir.org/r4");
        var ecosystem = new nzi.fhir.validator.core.terminology.TerminologySettings(
                nzi.fhir.validator.core.terminology.TerminologyMode.ECOSYSTEM, null);
        long base = HeapBudget.estimate(10 * MB, none, 5_000);
        assertEquals(HeapBudget.estimate(10 * MB), base, "mode none adds nothing");
        assertEquals(base + 5_000 * 1024L, HeapBudget.estimate(10 * MB, server, 5_000));
        assertEquals(base + 5_000 * 1024L, HeapBudget.estimate(10 * MB, ecosystem, 5_000));
        assertTrue(5_000 * 1024L <= 5 * MB, "within the SC-009 per-context bound");
        HeapBudget budget = new HeapBudget(() -> 4096 * MB, () -> 0, () -> { });
        assertEquals(HeapBudget.estimate(10 * MB) + 5_000 * 1024L, budget.reserve(10 * MB, 5_000 * 1024L));
    }
}
