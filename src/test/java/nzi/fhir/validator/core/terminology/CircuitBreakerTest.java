package nzi.fhir.validator.core.terminology;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-019, research R11: 5 consecutive failures open the circuit for 60 s, then one trial call. */
class CircuitBreakerTest {

    @Test
    void opensAfterFiveFailuresAndLetsOneTrialThroughAfterSixtySeconds() {
        AtomicLong now = new AtomicLong(1_000);
        CircuitBreaker b = new CircuitBreaker(now::get);
        for (int i = 0; i < 4; i++) {
            assertTrue(b.allow());
            b.onFailure();
        }
        assertTrue(b.allow(), "four failures: still closed");
        b.onFailure();
        assertTrue(b.isOpen());
        assertFalse(b.allow());
        now.addAndGet(59_999);
        assertFalse(b.allow(), "open for 60 s");
        now.addAndGet(1);
        assertTrue(b.allow(), "half-open: one trial");
        assertFalse(b.allow(), "only one trial at a time");
        b.onFailure();
        assertFalse(b.allow(), "a failed trial reopens it");
        now.addAndGet(60_000);
        assertTrue(b.allow());
        b.onSuccess();
        assertFalse(b.isOpen());
        assertTrue(b.allow() && b.allow(), "a success closes it");
    }

    @Test
    void aSuccessResetsTheCount() {
        CircuitBreaker b = new CircuitBreaker(() -> 0);
        for (int i = 0; i < 4; i++) {
            b.onFailure();
        }
        b.onSuccess();
        for (int i = 0; i < 4; i++) {
            b.onFailure();
        }
        assertFalse(b.isOpen());
    }
}
