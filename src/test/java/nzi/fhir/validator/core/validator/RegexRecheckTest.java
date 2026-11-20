package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirContextLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 013 FR-002 to FR-006, data-model "Re-check decision": the attempts are scripted results, the clock is fake, and
 * the gate and the rate limiter are fresh for each test (no JVM-wide state).
 */
class RegexRecheckTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private final AtomicLong now = new AtomicLong(1_000_000 * MS);
    private final ValidationGate gate = new ValidationGate();
    private final RecheckRateLimiter limiter = new RecheckRateLimiter(now::get);
    private final List<String> events = new ArrayList<>();
    private final RegexRecheck.Listener listener = new RegexRecheck.Listener() {
        @Override
        public void onRecheck(SupportedFhirVersion version, String kind) {
            events.add("recheck " + kind);
        }

        @Override
        public void onOutcome(SupportedFhirVersion version, String contextKey, String outcome, String reason, int attempts) {
            events.add(outcome + " " + reason + " " + attempts);
        }
    };

    private RegexRecheck recheck(long budgetMs) {
        return new RegexRecheck(budgetMs, listener, gate, limiter, now::get);
    }

    private RegexRecheck recheck() {
        return recheck(30_000);
    }

    static ValidationResult result(int timeouts) {
        List<SingleValidationMessage> messages = new ArrayList<>();
        for (int i = 0; i < timeouts; i++) {
            messages.add(RegexTimeoutIssueTest.message("REGEX_MATCH_TIMED_OUT", "The regex 'x" + i + "' could not be executed",
                    ResultSeverityEnum.ERROR));
        }
        messages.add(RegexTimeoutIssueTest.message(null, "Constraint failed: dom-6", ResultSeverityEnum.WARNING));
        return new ValidationResult(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4), messages);
    }

    /** One engine call per scripted result; each takes {@code durationMs} of fake time and records the gate mode. */
    private final class Script implements Supplier<ValidationResult> {
        final Deque<ValidationResult> results = new ArrayDeque<>();
        final List<ValidationResult> returned = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        long durationMs = 100;

        Script(int... timeoutsPerAttempt) {
            for (int t : timeoutsPerAttempt) {
                results.add(result(t));
            }
        }

        @Override
        public ValidationResult get() {
            modes.add(gate.exclusiveHeldByCurrentThread() ? "exclusive" : (gate.sharedHeldByCurrentThread() ? "shared" : "none"));
            now.addAndGet(durationMs * MS);
            ValidationResult r = results.removeFirst();
            returned.add(r);
            return r;
        }
    }

    private RegexRecheck.Result run(RegexRecheck recheck, Script script) {
        return recheck.run(script, OptionalLong.empty(), SupportedFhirVersion.R4, "test.ig#1.0.0");
    }

    @Test
    void noTimeoutIsOneSharedAttemptAndTheSameResult() {
        Script script = new Script(0);
        RegexRecheck.Result r = run(recheck(), script);
        assertSame(script.returned.get(0), r.result());
        assertEquals(1, r.attempts());
        assertEquals(RegexRecheck.OUTCOME_NONE, r.outcome());
        assertEquals(List.of("shared"), script.modes);
        assertEquals(List.of(), events, "nothing to report");
    }

    @Test
    void aTimeoutThenNoneIsResolvedByThePlainRecheck() {
        Script script = new Script(1, 0);
        RegexRecheck.Result r = run(recheck(), script);
        assertSame(script.returned.get(1), r.result(), "the re-check's own result, unchanged");
        assertEquals(2, r.attempts());
        assertEquals(RegexRecheck.OUTCOME_RESOLVED, r.outcome());
        assertEquals("", r.reason());
        assertEquals(List.of("shared", "shared"), script.modes);
        assertEquals(List.of("recheck plain", "resolved  2"), events);
    }

    @Test
    void twoTimeoutsThenNoneIsResolvedByTheQuietRecheckUnderTheExclusivePermit() {
        Script script = new Script(1, 1, 0);
        RegexRecheck.Result r = run(recheck(), script);
        assertSame(script.returned.get(2), r.result());
        assertEquals(3, r.attempts());
        assertEquals(RegexRecheck.OUTCOME_RESOLVED, r.outcome());
        assertEquals(List.of("shared", "shared", "exclusive"), script.modes);
        assertEquals(List.of("recheck plain", "recheck quiet", "resolved  3"), events);
    }

    @Test
    void aTimeoutOnEveryAttemptIsPersistentWithTheLastResultUnchanged() {
        Script script = new Script(1, 1, 1);
        RegexRecheck.Result r = run(recheck(), script);
        assertSame(script.returned.get(2), r.result());
        assertEquals(1, RegexTimeoutIssue.count(r.result()), "the timeout is reported as the engine reported it");
        assertEquals(3, r.attempts(), "FR-004: never more than three engine calls");
        assertEquals(RegexRecheck.OUTCOME_PERSISTENT, r.outcome());
        assertEquals(RegexRecheck.REASON_INPUT, r.reason());
        assertTrue(script.results.isEmpty());
    }

    // ---- US2: the safeguards (FR-006, research R4) ----

    @Test
    void moreThanThreeTimeoutsMeansInputAndSkipsTheQuietRecheck() {
        Script script = new Script(1, 4);
        RegexRecheck.Result r = run(recheck(), script);
        assertEquals(2, r.attempts());
        assertEquals(RegexRecheck.OUTCOME_PERSISTENT, r.outcome());
        assertEquals(RegexRecheck.REASON_MANY_TIMEOUTS, r.reason());
        assertEquals(List.of("shared", "shared"), script.modes);
    }

    @Test
    void theSeventhQuietRecheckWithinAMinuteIsSkippedUntilTheBucketRefills() {
        RegexRecheck recheck = recheck();
        for (int i = 0; i < 6; i++) {
            assertEquals(3, run(recheck, new Script(1, 1, 1)).attempts(), "quiet re-check " + (i + 1));
        }
        Script seventh = new Script(1, 1);
        RegexRecheck.Result r = run(recheck, seventh);
        assertEquals(2, r.attempts());
        assertEquals(RegexRecheck.REASON_QUIET_LIMIT, r.reason());
        now.addAndGet(TimeUnit.SECONDS.toNanos(10)); // capacity 6, refilled at 6 per minute: one token per 10 s
        assertEquals(RegexRecheck.OUTCOME_RESOLVED, run(recheck, new Script(1, 1, 0)).outcome());
        assertEquals(RegexRecheck.REASON_QUIET_LIMIT, run(recheck, new Script(1, 1)).reason(), "that token is spent again");
    }

    @Test
    void aTokenIsTakenOnlyWhenAQuietRecheckStarts() {
        RegexRecheck recheck = recheck();
        for (int i = 0; i < 10; i++) {
            assertEquals(RegexRecheck.REASON_MANY_TIMEOUTS, run(recheck, new Script(1, 5)).reason());
            assertEquals(RegexRecheck.OUTCOME_RESOLVED, run(recheck, new Script(1, 0)).outcome());
        }
        for (int i = 0; i < 6; i++) {
            assertEquals(3, run(recheck, new Script(1, 1, 1)).attempts(), "all 6 tokens are still there");
        }
    }

    @Test
    void theBudgetStopsThePlainRecheck() {
        Script script = new Script(1);
        script.durationMs = 600;
        RegexRecheck.Result r = run(recheck(1_000), script); // 600 ms spent + 2 x 600 ms > 1,000 ms
        assertEquals(1, r.attempts());
        assertEquals(RegexRecheck.REASON_BUDGET, r.reason());
    }

    @Test
    void theBudgetStopsTheQuietRecheck() {
        Script script = new Script(1, 1);
        script.durationMs = 300;
        RegexRecheck.Result r = run(recheck(1_000), script); // after plain: 600 ms spent + 2 x 300 ms > 1,000 ms
        assertEquals(2, r.attempts());
        assertEquals(RegexRecheck.REASON_BUDGET, r.reason());
    }

    @Test
    void aRequestDeadlineNearlyPassedStopsARecheckEvenWithValidationBudgetLeft() {
        Script script = new Script(1);
        RegexRecheck.Result r = recheck(30_000).run(script, OptionalLong.of(now.get() + 250 * MS), SupportedFhirVersion.R4,
                "test.ig#1.0.0"); // 100 ms spent + 2 x 100 ms > 250 ms
        assertEquals(1, r.attempts());
        assertEquals(RegexRecheck.REASON_BUDGET, r.reason());
    }

    @Test
    void anExclusivePermitNotGrantedWithinOneSecondIsBusyAndQueuedValidationsProceed() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch longRunning = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            pool.submit(() -> gate.shared(() -> {
                longRunning.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertTrue(longRunning.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            java.util.concurrent.Future<RegexRecheck.Result> quiet = pool.submit(() -> run(recheck(), new Script(1, 1)));
            Thread.sleep(200); // the quiet re-check now waits for the exclusive permit
            long queuedAt = System.nanoTime();
            assertEquals("ok", gate.shared(() -> "ok"), "a later validation runs once the quiet re-check gives up");
            long heldBackMs = (System.nanoTime() - queuedAt) / 1_000_000;
            RegexRecheck.Result r = quiet.get(5, TimeUnit.SECONDS);
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(RegexRecheck.REASON_BUSY, r.reason());
            assertEquals(2, r.attempts());
            assertTrue(waitedMs >= 900 && waitedMs < 3_000, "gave up after about 1 s: " + waitedMs + " ms");
            assertTrue(heldBackMs < 2_000, "held back " + heldBackMs + " ms");
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }
}
