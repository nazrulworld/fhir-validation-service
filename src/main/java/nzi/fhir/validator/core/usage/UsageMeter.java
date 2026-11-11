package nzi.fhir.validator.core.usage;

import nzi.fhir.validator.core.security.Caller;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Measures one validation request (spec 008 research R10): started by the validation endpoint, marked by the
 * validation service at each phase, and finished when <em>both</em> the response has ended and the validation work is
 * done (or never started). A latch of two makes sure exactly one record is emitted, also when a request times out
 * while its worker still runs: the record then carries the worker's real CPU time.
 * <p>
 * In <em>metrics-only</em> mode (usage sink {@code none}) the marks and the CPU and allocation deltas are still taken,
 * because they feed the metrics (FR-020); only the record isn't built. Marks may come from different threads.
 */
public class UsageMeter {
    /** Usage-only outcome: the endpoint answered 4xx before the validation service ran. */
    public static final String OUTCOME_REJECTED = "rejected";
    /** Usage-only outcome: the request timeout fired (wins over any later service outcome). */
    public static final String OUTCOME_TIMEOUT = "timeout";
    /** Usage-only outcome: the connection closed before the response ended (wins over any later service outcome). */
    public static final String OUTCOME_ABORTED = "aborted";

    /** Source of the per-thread counters; {@link ThreadMetrics} in production. */
    public interface Probe {
        long cpuNanos();

        long allocatedBytes();

        Probe THREAD = new Probe() {
            @Override
            public long cpuNanos() {
                return ThreadMetrics.cpuNanos();
            }

            @Override
            public long allocatedBytes() {
                return ThreadMetrics.allocatedBytes();
            }
        };
    }

    /** A meter that records nothing (callers outside HTTP: the command line, tests). */
    public static final UsageMeter NONE = new UsageMeter(System::nanoTime, Probe.THREAD, Instant.EPOCH, null, null, null,
            null, 0, null) {
        // Shared by every non-HTTP caller: nothing may write its fields.
        @Override
        public void contentType(String contentType) {
        }

        @Override
        public void profiles(int profiles) {
        }

        @Override
        public void ig(String ig) {
        }

        @Override
        public void outcome(String outcome) {
        }

        @Override
        public void markAdmitted() {
        }

        @Override
        public void markContextReady() {
        }

        @Override
        public void workerStart() {
        }

        @Override
        public void workerEnd(int fatal, int error, int warning, int information) {
        }

        @Override
        public void workDone() {
        }

        @Override
        public void workNeverStarted() {
        }

        @Override
        public void responseEnded(int status, boolean reset, boolean timedOut) {
        }
    };

    private static final long UNSET = Long.MIN_VALUE;

    private final LongSupplier clock;
    private final Probe probe;
    private final long entry;
    private final Instant startedAt;
    private final String requestId;
    private final Caller caller;
    private final String clientIp;
    private final String fhirVersion;
    private final int requestBytes;
    private final Consumer<UsageRecord> sink;
    private final AtomicInteger pending = new AtomicInteger(2);

    private volatile String contentType;
    private volatile int profiles;
    private volatile String ig;
    private volatile String serviceOutcome;
    private volatile long admitted = UNSET;
    private volatile long contextReady = UNSET;
    private volatile long workerStart = UNSET;
    private volatile long workerEnd = UNSET;
    private volatile long cpuStart = -1;
    private volatile long allocStart = -1;
    private volatile long cpuNanos = -1;
    private volatile long allocatedBytes = -1;
    private volatile int fatal;
    private volatile int error;
    private volatile int warning;
    private volatile int information;
    private volatile int status;
    private volatile boolean reset;
    private volatile boolean timedOut;

    /**
     * @param sink receives the record once; {@code null} = metrics-only mode
     */
    public UsageMeter(LongSupplier clock, Probe probe, Instant startedAt, String requestId, Caller caller, String clientIp,
                      String fhirVersion, int requestBytes, Consumer<UsageRecord> sink) {
        this.clock = clock;
        this.probe = probe;
        this.entry = clock.getAsLong();
        this.startedAt = startedAt;
        this.requestId = requestId;
        this.caller = caller;
        this.clientIp = clientIp;
        this.fhirVersion = fhirVersion;
        this.requestBytes = requestBytes;
        this.sink = sink;
    }

    /** A meter for a request that starts now, on the system clock and the current-thread counters. */
    public static UsageMeter start(String requestId, Caller caller, String clientIp, String fhirVersion, int requestBytes,
                                   Consumer<UsageRecord> sink) {
        return new UsageMeter(System::nanoTime, Probe.THREAD, Instant.now(), requestId, caller, clientIp, fhirVersion,
                requestBytes, sink);
    }

    public void contentType(String contentType) {
        this.contentType = contentType;
    }

    public void profiles(int profiles) {
        this.profiles = profiles;
    }

    /** The validator context used ({@code name#version} or {@code base}), or the requested reference. */
    public void ig(String ig) {
        this.ig = ig;
    }

    /** The service's outcome ({@code ServiceMetrics.OUTCOME_*}). */
    public void outcome(String outcome) {
        this.serviceOutcome = outcome;
    }

    /** The admission controller let the request in. */
    public void markAdmitted() {
        admitted = clock.getAsLong();
    }

    /** The validator context is ready. */
    public void markContextReady() {
        contextReady = clock.getAsLong();
    }

    /** First thing on the validation worker thread. */
    public void workerStart() {
        workerStart = clock.getAsLong();
        cpuStart = probe.cpuNanos();
        allocStart = probe.allocatedBytes();
    }

    /** Last thing on the validation worker thread, with the issue counts by severity. */
    public void workerEnd(int fatal, int error, int warning, int information) {
        long cpu = probe.cpuNanos();
        long alloc = probe.allocatedBytes();
        cpuNanos = cpu < 0 || cpuStart < 0 ? -1 : cpu - cpuStart;
        allocatedBytes = alloc < 0 || allocStart < 0 ? -1 : alloc - allocStart;
        this.fatal = fatal;
        this.error = error;
        this.warning = warning;
        this.information = information;
        workerEnd = clock.getAsLong();
    }

    /** CPU time of the validation work, -1 when unknown or not reached (metrics). */
    public long cpuNanos() {
        return cpuNanos;
    }

    /** Bytes allocated by the validation work, -1 when unknown or not reached (metrics). */
    public long allocatedBytes() {
        return allocatedBytes;
    }

    /** The validation service's future completed. */
    public void workDone() {
        countDown();
    }

    /** The endpoint answered before calling the validation service. */
    public void workNeverStarted() {
        countDown();
    }

    /**
     * The response ended ({@code reset}: the connection closed first; {@code timedOut}: the request timeout answered).
     */
    public void responseEnded(int status, boolean reset, boolean timedOut) {
        this.status = status;
        this.reset = reset;
        this.timedOut = timedOut;
        countDown();
    }

    private void countDown() {
        if (pending.decrementAndGet() == 0 && sink != null) {
            sink.accept(record(clock.getAsLong()));
        }
    }

    /** The outcome, by precedence: timeout, aborted, the service's, rejected (4xx), error. */
    String outcome() {
        if (timedOut) {
            return OUTCOME_TIMEOUT;
        }
        if (reset) {
            return OUTCOME_ABORTED;
        }
        if (serviceOutcome != null) {
            return serviceOutcome;
        }
        return status >= 400 && status < 500 ? OUTCOME_REJECTED : "error";
    }

    UsageRecord record(long end) {
        long queue = admitted == UNSET ? -1
                : (admitted - entry) + (workerStart != UNSET && contextReady != UNSET ? workerStart - contextReady : 0);
        long context = admitted != UNSET && contextReady != UNSET ? contextReady - admitted : -1;
        long validate = workerStart != UNSET && workerEnd != UNSET ? workerEnd - workerStart : -1;
        UUID keyId = caller == null ? null : caller.apiKeyId();
        return new UsageRecord(requestId, startedAt, keyId, caller == null ? null : caller.apiKeyName(),
                caller == null ? null : caller.orgId(), fhirVersion, ig, profiles, contentType, requestBytes, outcome(),
                status, fatal, error, warning, information, clientIp, ms(end - entry), queue < 0 ? -1 : ms(queue),
                context < 0 ? -1 : ms(context), validate < 0 ? -1 : ms(validate), cpuNanos, allocatedBytes);
    }

    private static int ms(long nanos) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos)));
    }
}
