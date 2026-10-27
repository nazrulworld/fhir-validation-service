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
        check(expandedBytes);
        long needed = estimate(expandedBytes);
        reserved.addAndGet(needed);
        return needed;
    }

    public void release(long reservedBytes) {
        reserved.addAndGet(-reservedBytes);
    }

    /**
     * For an include that is about to be committed: 413 {@code package-too-large} if it would not fit.
     */
    public void admitInclude(long expandedBytes) {
        try {
            check(expandedBytes);
        } catch (InsufficientHeapException e) {
            throw new ProblemException(413, "package-too-large",
                    "This instance cannot build a validator with this IG set: it " + e.getMessage());
        }
    }

    private long free() {
        return maxHeap.getAsLong() - usedHeap.getAsLong() - reserved.get();
    }
}
