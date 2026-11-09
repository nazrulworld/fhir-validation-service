package nzi.fhir.validator.core.terminology;

import java.util.function.LongSupplier;

/**
 * Skips a terminology server that keeps failing (spec 007 FR-019, research R11): after {@value #THRESHOLD} consecutive
 * failures it opens for {@value #OPEN_MS} ms; then one trial call is let through (half-open), and its outcome closes
 * or reopens it. Thread-safe.
 */
public final class CircuitBreaker {
    static final int THRESHOLD = 5;
    static final long OPEN_MS = 60_000;

    private final LongSupplier clock;
    private int failures;
    private long openUntil;
    private boolean trialInFlight;

    public CircuitBreaker() {
        this(System::currentTimeMillis);
    }

    CircuitBreaker(LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether a call may be made now. */
    public synchronized boolean allow() {
        if (failures < THRESHOLD) {
            return true;
        }
        if (clock.getAsLong() < openUntil || trialInFlight) {
            return false;
        }
        trialInFlight = true;
        return true;
    }

    public synchronized void onSuccess() {
        failures = 0;
        trialInFlight = false;
    }

    public synchronized void onFailure() {
        trialInFlight = false;
        failures++;
        if (failures >= THRESHOLD) {
            openUntil = clock.getAsLong() + OPEN_MS;
        }
    }

    public synchronized boolean isOpen() {
        return failures >= THRESHOLD;
    }
}
