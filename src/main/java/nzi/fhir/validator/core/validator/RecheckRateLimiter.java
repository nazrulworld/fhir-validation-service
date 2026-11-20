package nzi.fhir.validator.core.validator;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The quiet re-checks a JVM may start (spec 013 research R4): a token bucket of capacity {@value #CAPACITY}, refilled at
 * {@value #CAPACITY} per minute. A quiet re-check pauses every other validation on the instance for a moment; the
 * bucket caps how often a stream of hostile resources can cause that.
 */
public final class RecheckRateLimiter {
    static final int CAPACITY = 6;
    static final long REFILL_NANOS = TimeUnit.MINUTES.toNanos(1) / CAPACITY;

    /** The limiter of this JVM. */
    public static final RecheckRateLimiter INSTANCE = new RecheckRateLimiter(System::nanoTime);

    private final LongSupplier nanoClock;
    private double tokens = CAPACITY;
    private long refilledAt;

    RecheckRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.refilledAt = nanoClock.getAsLong();
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(CAPACITY, tokens + (double) (now - refilledAt) / REFILL_NANOS);
        refilledAt = now;
    }

    /** Whether a token is available now (none is taken). */
    public synchronized boolean available() {
        refill();
        return tokens >= 1;
    }

    /** Takes a token when one is available. */
    public synchronized boolean tryTake() {
        refill();
        if (tokens < 1) {
            return false;
        }
        tokens--;
        return true;
    }

    /** Fills the bucket again (tests: no test sees the tokens another one used). */
    synchronized void reset() {
        tokens = CAPACITY;
        refilledAt = nanoClock.getAsLong();
    }
}
