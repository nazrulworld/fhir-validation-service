package nzi.fhir.validator.core.validator;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The permit every validation of this JVM holds while the engine runs (spec 013 research R3). Validations hold a
 * shared permit and run side by side; a quiet re-check takes the exclusive one, so it runs with no other validation
 * competing for the CPU. The lock is fair: once an exclusive request waits, later shared requests queue behind it, so
 * the caller bounds that wait (at most {@code RegexRecheck.QUIET_WAIT_MAX_NANOS}).
 * <p>
 * Blocks: call it from worker threads only, never from an event loop (Constitution I). A thread never asks for the
 * exclusive permit while it holds a shared one (that would deadlock); such a call is refused.
 */
public final class ValidationGate {
    /** The gate of this JVM: the CPU it protects is the JVM's. */
    public static final ValidationGate INSTANCE = new ValidationGate();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** Runs {@code work} under a shared permit, waiting while an exclusive one is held or requested. */
    public <T> T shared(Supplier<T> work) {
        lock.readLock().lock();
        try {
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Runs {@code work} under the exclusive permit, waiting at most {@code timeoutNanos} for the validations already
     * running to finish.
     *
     * @return empty when the permit was not granted in time (or the thread was interrupted); {@code work} did not run
     * @throws IllegalStateException when this thread holds a shared permit
     */
    public <T> Optional<T> exclusive(Supplier<T> work, long timeoutNanos) {
        if (lock.getReadHoldCount() > 0) {
            throw new IllegalStateException("A validation can't ask for the exclusive permit while it holds a shared one");
        }
        boolean granted;
        try {
            granted = lock.writeLock().tryLock(Math.max(0, timeoutNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!granted) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean exclusiveHeldByCurrentThread() {
        return lock.isWriteLockedByCurrentThread();
    }

    public boolean sharedHeldByCurrentThread() {
        return lock.getReadHoldCount() > 0;
    }

    /** Whether a thread waits for a permit (tests). */
    boolean hasQueuedRequests() {
        return lock.hasQueuedThreads();
    }
}
