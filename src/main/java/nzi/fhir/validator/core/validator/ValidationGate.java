package nzi.fhir.validator.core.validator;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The permit every validation of this JVM holds while the engine runs (spec 013 research R3). Validations hold a
 * shared permit and run side by side; a quiet re-check takes the exclusive one, so it runs with no other validation
 * competing for the CPU.
 * <p>
 * Two bounds keep a quiet re-check from pausing the instance (FR-006, US2 scenario 3):
 * <ul>
 *     <li>the wait: once an exclusive request waits, later shared requests queue behind it, so the caller bounds that
 *     wait (at most {@code RegexRecheck.QUIET_WAIT_MAX_NANOS});</li>
 *     <li>the hold: the exclusive permit is a lease of {@link #EXCLUSIVE_LEASE_NANOS}. When the work runs longer, shared
 *     requests are admitted again and the work goes on beside them, as under a shared permit; a slow or hostile
 *     resource never holds the others back for longer than the lease.</li>
 * </ul>
 * Blocks: call it from worker threads only, never from an event loop (Constitution I). A thread never asks for the
 * exclusive permit while it holds a shared one (that would deadlock); such a call is refused.
 */
public final class ValidationGate {
    /** The longest the exclusive permit keeps shared requests out. */
    public static final long EXCLUSIVE_LEASE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The gate of this JVM: the CPU it protects is the JVM's. */
    public static final ValidationGate INSTANCE = new ValidationGate();

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final ThreadLocal<int[]> sharedHolds = ThreadLocal.withInitial(() -> new int[1]);
    private final long leaseNanos;
    private final LongSupplier nanoClock;

    // Guarded by lock.
    private int sharedActive;
    private int sharedWaiting;
    private int exclusiveWaiting;
    private Thread exclusiveOwner;
    private long leaseEnd;

    public ValidationGate() {
        this(EXCLUSIVE_LEASE_NANOS, System::nanoTime);
    }

    ValidationGate(long leaseNanos, LongSupplier nanoClock) {
        this.leaseNanos = leaseNanos;
        this.nanoClock = nanoClock;
    }

    /** Nanos until the current lease ends; 0 when no exclusive permit keeps shared requests out. Holds lock. */
    private long leaseLeft() {
        return exclusiveOwner == null ? 0 : Math.max(0, leaseEnd - nanoClock.getAsLong());
    }

    /**
     * Runs {@code work} under a shared permit, waiting while an exclusive lease runs or an exclusive request waits.
     */
    public <T> T shared(Supplier<T> work) {
        lock.lock();
        try {
            // Uninterruptible like the validation itself; both waits are bounded (lease, exclusive request's timeout).
            boolean interrupted = false;
            sharedWaiting++;
            try {
                while (true) {
                    long left = leaseLeft();
                    if (left == 0 && exclusiveWaiting == 0) {
                        break;
                    }
                    if (left > 0) {
                        try {
                            changed.awaitNanos(left); // nobody signals the lease's end
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    } else {
                        changed.awaitUninterruptibly();
                    }
                }
            } finally {
                sharedWaiting--;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            sharedActive++;
        } finally {
            lock.unlock();
        }
        sharedHolds.get()[0]++;
        try {
            return work.get();
        } finally {
            sharedHolds.get()[0]--;
            lock.lock();
            try {
                sharedActive--;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Runs {@code work} under the exclusive permit, waiting at most {@code timeoutNanos} for the validations already
     * running to finish. Shared requests are kept out for at most the lease; after it {@code work} goes on beside them.
     *
     * @return empty when the permit was not granted in time (or the thread was interrupted); {@code work} did not run
     * @throws IllegalStateException when this thread holds a shared permit
     */
    public <T> Optional<T> exclusive(Supplier<T> work, long timeoutNanos) {
        if (sharedHeldByCurrentThread()) {
            throw new IllegalStateException("A validation can't ask for the exclusive permit while it holds a shared one");
        }
        lock.lock();
        try {
            long left = Math.max(0, timeoutNanos);
            exclusiveWaiting++;
            try {
                while (sharedActive > 0 || exclusiveOwner != null) {
                    if (left <= 0) {
                        return Optional.empty();
                    }
                    left = changed.awaitNanos(left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                exclusiveWaiting--;
                changed.signalAll(); // shared requests queued behind this one may go on
            }
            exclusiveOwner = Thread.currentThread();
            leaseEnd = nanoClock.getAsLong() + leaseNanos;
        } finally {
            lock.unlock();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            lock.lock();
            try {
                exclusiveOwner = null;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean exclusiveHeldByCurrentThread() {
        lock.lock();
        try {
            return exclusiveOwner == Thread.currentThread();
        } finally {
            lock.unlock();
        }
    }

    public boolean sharedHeldByCurrentThread() {
        return sharedHolds.get()[0] > 0;
    }

    /** Whether a thread waits for a permit (tests). */
    boolean hasQueuedRequests() {
        lock.lock();
        try {
            return sharedWaiting > 0 || exclusiveWaiting > 0;
        } finally {
            lock.unlock();
        }
    }
}
