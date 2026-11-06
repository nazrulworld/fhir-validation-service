package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Keeps a validator build from exhausting the heap (spec edge case "a rebuild fails": the process doesn't crash).
 * The container runs with {@code -XX:+ExitOnOutOfMemoryError}, and an included IG is committed before it is built,
 * so a build that does not fit would end the process on every restart and take every FHIR version down.
 * <p>
 * A build keeps its parsed packages and conformance resources in memory, and the version's previous snapshot stays
 * live until the swap. Measured with HAPI 8 (R4): retained heap is 3.4 to 9 times the packages' expanded size
 * ({@code hl7.fhir.r4.examples#4.0.1}: 180 MB expanded, 612 MB retained), plus transient parsing garbage. The
 * estimate is deliberately on the high side; four warmed core validators alone take about 0.9 GB.
 * <p>
 * Run {@link #check} on a worker thread: when a build looks too large it triggers a full collection before deciding,
 * so garbage left by earlier builds does not cause a refusal.
 */
public class HeapBudget {
    private static final Logger logger = LogManager.getLogger(HeapBudget.class);
    private static final long MB = 1024L * 1024L;
    /** Heap needed per expanded package byte, including transient garbage during the build. */
    static final int HEAP_PER_EXPANDED_BYTE = 6;
    static final long BUILD_OVERHEAD_BYTES = 64 * MB;

    /**
     * A build or include that would not fit in this instance's heap. The message is curated (Principle IV).
     */
    public static class InsufficientHeapException extends RuntimeException {
        InsufficientHeapException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Would fit in this heap, but not next to the contexts that cannot be evicted (base and pinned ones): an
     * on-demand request answers 503 {@code insufficient-capacity} (spec 004 FR-016).
     */
    public static class InsufficientCapacityException extends InsufficientHeapException {
        InsufficientCapacityException(String message) {
            super(message);
        }
    }

    /** Larger than the whole heap: it can never be built here (413 {@code package-too-large}, spec 004 FR-016). */
    public static class NeverFitsException extends InsufficientHeapException {
        NeverFitsException(String message) {
            super(message);
        }
    }

    /**
     * Frees memory on request by dropping one evictable validator context (spec 004 FR-015): returns {@code false}
     * when nothing is left to evict.
     */
    @FunctionalInterface
    public interface Evictor {
        Evictor NONE = () -> false;

        boolean evictOne();
    }

    private volatile LongSupplier maxHeap;
    private final LongSupplier usedHeap;
    private final Runnable collect;
    private final AtomicLong reserved = new AtomicLong();

    /**
     * @param maxHeap  maximum heap size in bytes
     * @param usedHeap heap in use, in bytes (may include garbage)
     * @param collect  runs a full collection, so {@code usedHeap} is close to the live set afterwards
     */
    public HeapBudget(LongSupplier maxHeap, LongSupplier usedHeap, Runnable collect) {
        this.maxHeap = maxHeap;
        this.usedHeap = usedHeap;
        this.collect = collect;
    }

    public static HeapBudget ofRuntime() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return new HeapBudget(() -> Runtime.getRuntime().maxMemory(), () -> memory.getHeapMemoryUsage().getUsed(),
                System::gc);
    }

    /** Test hook: a different heap limit (e.g. a tiny one, so every build is refused). */
    public void setMaxHeap(LongSupplier maxHeap) {
        this.maxHeap = maxHeap;
    }

    /** Estimated peak heap of a build over packages of {@code expandedBytes} in total. */
    public static long estimate(long expandedBytes) {
        return BUILD_OVERHEAD_BYTES + HEAP_PER_EXPANDED_BYTE * expandedBytes;
    }

    /**
     * @throws InsufficientHeapException if a build over {@code expandedBytes} would not fit next to what is live now
     *                                   and the builds already admitted
     */
    public void check(long expandedBytes) {
        long needed = estimate(expandedBytes);
        if (needed <= free()) {
            return;
        }
        collect.run();
        long free = free();
        if (needed > free) {
            logger.warn("Refusing a validator build: needs about {} MB of heap, {} MB free of {} MB", needed / MB,
                    Math.max(0, free) / MB, maxHeap.getAsLong() / MB);
            throw new InsufficientHeapException("needs about " + needed / MB + " MB of heap but about "
                    + Math.max(0, free) / MB + " MB is free; raise FV_MEMORY_LIMIT (or the JVM heap)");
        }
    }

    /**
     * {@link #check}s, then holds the estimate until {@link #release} so concurrent builds do not each count on the
     * same free heap.
     *
     * @return the reserved bytes, to pass to {@link #release}
     */
    public synchronized long reserve(long expandedBytes) {
        return reserve(expandedBytes, Evictor.NONE);
    }

    /**
     * Like {@link #reserve(long)}, but when the build does not fit, evicts contexts through {@code evictor} one at a
     * time (each followed by a collection) until it fits or nothing evictable is left (spec 004, research R7).
     *
     * @throws NeverFitsException           if the estimate exceeds the whole heap
     * @throws InsufficientCapacityException if it would fit, but not next to what cannot be evicted
     */
    public synchronized long reserve(long expandedBytes, Evictor evictor) {
        fitOrEvict(expandedBytes, evictor);
        long needed = estimate(expandedBytes);
        reserved.addAndGet(needed);
        return needed;
    }

    private void fitOrEvict(long expandedBytes, Evictor evictor) {
        long needed = estimate(expandedBytes);
        if (needed > maxHeap.getAsLong()) {
            throw new NeverFitsException("needs about " + needed / MB + " MB of heap, more than the whole heap of "
                    + maxHeap.getAsLong() / MB + " MB; raise FV_MEMORY_LIMIT (or the JVM heap)");
        }
        while (true) {
            try {
                check(expandedBytes);
                return;
            } catch (InsufficientHeapException e) {
                if (!evictor.evictOne()) {
                    if (evictor == Evictor.NONE) {
                        throw e;
                    }
                    throw new InsufficientCapacityException(e.getMessage());
                }
                logger.info("Evicted an on-demand validator context to make room for a build of about {} MB", needed / MB);
            }
        }
    }

    public void release(long reservedBytes) {
        reserved.addAndGet(-reservedBytes);
    }

    /**
     * For an include that is about to be committed: 413 {@code package-too-large} if it would not fit.
     */
    public void admitInclude(long expandedBytes) {
        admitInclude(expandedBytes, Evictor.NONE);
    }

    /**
     * {@link #admitInclude(long)}, evicting on-demand contexts first: administrator choices take priority over
     * on-demand caching (spec 004, research R7).
     */
    public synchronized void admitInclude(long expandedBytes, Evictor evictor) {
        try {
            fitOrEvict(expandedBytes, evictor);
        } catch (InsufficientHeapException e) {
            throw new ProblemException(413, "package-too-large",
                    "This instance cannot build a validator with this IG set: it " + e.getMessage());
        }
    }

    private long free() {
        return maxHeap.getAsLong() - usedHeap.getAsLong() - reserved.get();
    }
}
