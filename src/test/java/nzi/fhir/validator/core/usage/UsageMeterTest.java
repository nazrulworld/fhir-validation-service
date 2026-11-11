package nzi.fhir.validator.core.usage;

import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 research R10: phase arithmetic, exactly-once emission and outcome precedence. */
class UsageMeterTest {
    private static final long MS = 1_000_000L;
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Caller CALLER = Caller.of(ORG, "acme", ApiKeyRepository.Role.VALIDATE, KEY, "acme-ci");

    private final AtomicLong now = new AtomicLong(1_000 * MS);
    private final AtomicLong cpu = new AtomicLong(5_000);
    private final AtomicLong alloc = new AtomicLong(100);
    private final List<UsageRecord> records = new ArrayList<>();

    private final UsageMeter.Probe probe = new UsageMeter.Probe() {
        @Override
        public long cpuNanos() {
            return cpu.get();
        }

        @Override
        public long allocatedBytes() {
            return alloc.get();
        }
    };

    private UsageMeter meter(UsageMeter.Probe p, boolean full) {
        return new UsageMeter(now::get, p, Instant.parse("2026-11-11T10:00:00Z"), "req-1", CALLER, "203.0.113.7", "R4", 42,
                full ? records::add : null);
    }

    private void advance(long ms) {
        now.addAndGet(ms * MS);
    }

    /** entry → +2 admitted → +3 context → +4 worker start → +10 worker end → +1 response end. */
    private UsageMeter completed(UsageMeter m) {
        advance(2);
        m.markAdmitted();
        advance(3);
        m.markContextReady();
        advance(4);
        m.workerStart();
        cpu.addAndGet(7_000_000);
        alloc.addAndGet(2_048);
        advance(10);
        m.workerEnd(1, 2, 3, 4);
        return m;
    }

    @Test
    void phasesAddUp() {
        UsageMeter m = completed(meter(probe, true));
        m.contentType("JSON");
        m.profiles(2);
        m.ig("a.ig#1.0.0");
        m.outcome("invalid");
        m.workDone();
        assertTrue(records.isEmpty(), "nothing until the response has ended too");
        advance(1);
        m.responseEnded(200, false, false);

        assertEquals(1, records.size());
        UsageRecord r = records.get(0);
        assertEquals(20, r.totalMs());
        assertEquals(2 + 4, r.queueMs(), "admission wait + worker wait");
        assertEquals(3, r.contextMs());
        assertEquals(10, r.validateMs());
        assertEquals(7_000_000, r.cpuTimeNs());
        assertEquals(2_048, r.allocatedBytes());
        assertEquals(List.of(1, 2, 3, 4), List.of(r.issuesFatal(), r.issuesError(), r.issuesWarning(), r.issuesInformation()));
        assertEquals("invalid", r.outcome());
        assertEquals(200, r.httpStatus());
        assertEquals(KEY, r.apiKeyId());
        assertEquals("acme-ci", r.apiKeyName());
        assertEquals(ORG, r.orgId());
        assertEquals("R4", r.fhirVersion());
        assertEquals("a.ig#1.0.0", r.ig());
        assertEquals(2, r.profiles());
        assertEquals("JSON", r.contentType());
        assertEquals(42, r.requestBytes());
        assertEquals("203.0.113.7", r.clientIp());
        assertEquals("req-1", r.requestId());
    }

    @Test
    void emitsExactlyOnceWhicheverEndsFirst() {
        UsageMeter m = completed(meter(probe, true));
        m.responseEnded(200, false, false);
        m.outcome("valid");
        m.workDone();
        m.workDone();
        m.responseEnded(200, false, false);
        assertEquals(1, records.size());
    }

    @Test
    void totalIsNeverLessThanValidateEvenAfterATimeout() {
        UsageMeter m = meter(probe, true);
        advance(1);
        m.markAdmitted();
        m.markContextReady();
        m.workerStart();
        cpu.addAndGet(3_000_000);
        advance(5);
        m.responseEnded(503, false, true); // the timeout answered; the worker is still running
        advance(40);
        m.workerEnd(0, 0, 0, 0);
        m.outcome("valid"); // a late success never overwrites the timeout
        m.workDone();

        UsageRecord r = records.get(0);
        assertEquals("timeout", r.outcome());
        assertEquals(503, r.httpStatus());
        assertEquals(3_000_000, r.cpuTimeNs(), "the worker's real CPU time");
        assertTrue(r.totalMs() >= r.validateMs(), r.toString());
    }

    @Test
    void aResetBeforeTheEndIsAborted() {
        UsageMeter m = completed(meter(probe, true));
        m.responseEnded(200, true, false);
        m.outcome("invalid");
        m.workDone();
        assertEquals("aborted", records.get(0).outcome());
    }

    @Test
    void rejectedBeforeTheWorkStarted() {
        UsageMeter m = meter(probe, true);
        m.workNeverStarted();
        advance(1);
        m.responseEnded(406, false, false);
        UsageRecord r = records.get(0);
        assertEquals("rejected", r.outcome());
        assertEquals(List.of(-1, -1, -1), List.of(r.queueMs(), r.contextMs(), r.validateMs()));
        assertEquals(-1, r.cpuTimeNs());
        assertEquals(-1, r.allocatedBytes());
        assertEquals(1, r.totalMs());
    }

    @Test
    void unsupportedCountersAreMinusOne() {
        UsageMeter.Probe none = new UsageMeter.Probe() {
            @Override
            public long cpuNanos() {
                return -1;
            }

            @Override
            public long allocatedBytes() {
                return -1;
            }
        };
        UsageMeter m = completed(meter(none, true));
        m.outcome("valid");
        m.workDone();
        m.responseEnded(200, false, false);
        assertEquals(-1, records.get(0).cpuTimeNs());
        assertEquals(-1, records.get(0).allocatedBytes());
        assertEquals(10, records.get(0).validateMs(), "timings are still measured");
    }

    @Test
    void metricsOnlyModeMeasuresButNeverEmits() {
        UsageMeter m = completed(meter(probe, false));
        m.outcome("valid");
        m.workDone();
        m.responseEnded(200, false, false);
        assertTrue(records.isEmpty());
        assertEquals(7_000_000, m.cpuNanos());
        assertEquals(2_048, m.allocatedBytes());
    }

    @Test
    void noneDoesNothing() {
        UsageMeter.NONE.markAdmitted();
        UsageMeter.NONE.workerStart();
        UsageMeter.NONE.workerEnd(1, 1, 1, 1);
        UsageMeter.NONE.workDone();
        UsageMeter.NONE.responseEnded(200, false, false);
        assertEquals(-1, UsageMeter.NONE.cpuNanos());
    }
}
