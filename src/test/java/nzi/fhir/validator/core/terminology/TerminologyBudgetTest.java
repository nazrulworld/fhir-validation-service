package nzi.fhir.validator.core.terminology;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-019a, research R5: only charged terminology time counts; never a wall-clock deadline. */
class TerminologyBudgetTest {

    @AfterEach
    void tearDown() {
        TerminologyBudget.close();
    }

    @Test
    void chargesReduceTheBudgetAndTimeAloneDoesNot() throws InterruptedException {
        TerminologyBudget.open(1_000);
        assertEquals(1_000, TerminologyBudget.remainingMs());
        Thread.sleep(50);
        assertEquals(1_000, TerminologyBudget.remainingMs(), "uncharged time (local validation work) never counts");
        TerminologyBudget.charge(400);
        assertEquals(600, TerminologyBudget.remainingMs());
        assertFalse(TerminologyBudget.exhausted());
        TerminologyBudget.charge(700);
        assertEquals(0, TerminologyBudget.remainingMs());
        assertTrue(TerminologyBudget.exhausted());
    }

    @Test
    void degradedAndExhaustedFlags() {
        TerminologyBudget.open(100);
        assertFalse(TerminologyBudget.degraded());
        TerminologyBudget.markDegraded();
        assertTrue(TerminologyBudget.degraded());
        assertFalse(TerminologyBudget.budgetExhausted());
        TerminologyBudget.markBudgetExhausted();
        assertTrue(TerminologyBudget.budgetExhausted());
    }

    @Test
    void budgetsDoNotNestAndAClosedOneIsUnlimited() {
        TerminologyBudget.open(10);
        assertThrows(IllegalStateException.class, () -> TerminologyBudget.open(10));
        TerminologyBudget.close();
        assertFalse(TerminologyBudget.isOpen());
        assertEquals(Long.MAX_VALUE, TerminologyBudget.remainingMs());
        assertFalse(TerminologyBudget.exhausted());
        TerminologyBudget.markDegraded();
        assertFalse(TerminologyBudget.degraded(), "nothing is recorded without a budget");
    }

    @Test
    void aWarmUpBudgetOfZeroAllowsNoCall() {
        TerminologyBudget.open(0);
        assertTrue(TerminologyBudget.exhausted());
    }

    @Test
    void budgetsArePerThread() throws Exception {
        TerminologyBudget.open(100);
        TerminologyBudget.charge(100);
        Thread other = new Thread(() -> assertFalse(TerminologyBudget.isOpen()));
        other.start();
        other.join();
        assertTrue(TerminologyBudget.exhausted());
    }
}
