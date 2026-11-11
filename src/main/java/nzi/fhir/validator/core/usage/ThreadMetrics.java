package nzi.fhir.validator.core.usage;

import org.apache.logging.log4j.LogManager;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Per-thread CPU time and allocated bytes (spec 008 research R10): the only honest per-request measures on pooled
 * worker threads with a shared heap. Each value is -1 when the JVM doesn't support or has disabled the counter; that
 * is checked once.
 */
public final class ThreadMetrics {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU = cpuSupported();
    private static final com.sun.management.ThreadMXBean ALLOC = allocSupported();

    static {
        if (!CPU || ALLOC == null) {
            LogManager.getLogger(ThreadMetrics.class).info("Per-request {}{}{} not available on this JVM; recorded as -1",
                    CPU ? "" : "CPU time", !CPU && ALLOC == null ? " and " : "", ALLOC == null ? "allocated bytes" : "");
        }
    }

    private ThreadMetrics() {
    }

    /** CPU time of the current thread, in nanoseconds, or -1. */
    public static long cpuNanos() {
        return CPU ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /** Bytes allocated by the current thread so far, or -1. */
    public static long allocatedBytes() {
        return ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static boolean cpuSupported() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static com.sun.management.ThreadMXBean allocSupported() {
        try {
            if (THREADS instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()
                    && sun.isThreadAllocatedMemoryEnabled()) {
                return sun;
            }
        } catch (UnsupportedOperationException | LinkageError e) {
            // not a HotSpot-compatible JVM
        }
        return null;
    }
}
