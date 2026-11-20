package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ValidationResult;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Re-checks a validation whose result reports a regex timeout (spec 013 FR-002 to FR-006, data-model "Re-check
 * decision"). HL7 core stops every regex check after 500 ms of wall time, so under load a cheap regex can time out
 * before it runs. The engine is called again at most twice: a plain re-check at once (a GC pause or a CPU spike rarely
 * repeats), then a quiet re-check under the gate's exclusive permit, with no other validation running. The answer is
 * one attempt's complete result, never merged or edited (Constitution III): the first without a regex timeout, or the
 * last one.
 * <p>
 * One object per service instance (its validation budget and metrics); the gate and the rate limiter are the JVM's.
 * Runs on worker threads only.
 */
public final class RegexRecheck {
    private static final Logger logger = LogManager.getLogger(RegexRecheck.class);

    public static final String OUTCOME_NONE = "none";
    public static final String OUTCOME_RESOLVED = "resolved";
    public static final String OUTCOME_PERSISTENT = "persistent";
    public static final String REASON_INPUT = "input";
    public static final String REASON_MANY_TIMEOUTS = "many-timeouts";
    public static final String REASON_QUIET_LIMIT = "quiet-limit";
    public static final String REASON_BUDGET = "budget";
    public static final String REASON_BUSY = "busy";
    /** More regex timeouts than this in one result point at the input, not at load: no quiet re-check (research R4). */
    static final int MAX_TIMEOUTS_FOR_QUIET = 3;
    /** The longest a quiet re-check waits for the exclusive permit; validations queued behind it wait as long. */
    public static final long QUIET_WAIT_MAX_NANOS = TimeUnit.SECONDS.toNanos(1);
    public static final String KIND_PLAIN = "plain";
    public static final String KIND_QUIET = "quiet";

    /** Reports re-checks and how timeouts ended (spec 013 FR-008). */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        default void onRecheck(SupportedFhirVersion version, String kind) {
        }

        default void onOutcome(SupportedFhirVersion version, String contextKey, String outcome, String reason, int attempts) {
        }
    }

    /**
     * @param result   the answer: one attempt's complete result
     * @param attempts engine calls made (1 to 3)
     * @param outcome  {@link #OUTCOME_NONE} (no timeout), {@link #OUTCOME_RESOLVED} or {@link #OUTCOME_PERSISTENT}
     * @param reason   why a timeout persisted; empty otherwise
     */
    public record Result(ValidationResult result, int attempts, String outcome, String reason) {
    }

    /** For validations without a service instance (warm-up, tests): the default budget, nothing reported. */
    public static final RegexRecheck DEFAULT = new RegexRecheck(30_000, Listener.NONE);

    private final long validationBudgetNanos;
    private final Listener listener;
    private final ValidationGate gate;
    private final RecheckRateLimiter limiter;
    private final LongSupplier nanoClock;

    /**
     * @param validationBudgetMs {@code FV_VALIDATION_TIMEOUT_MS}: a validation's attempts stay within it
     */
    public RegexRecheck(long validationBudgetMs, Listener listener) {
        this(validationBudgetMs, listener, ValidationGate.INSTANCE, RecheckRateLimiter.INSTANCE, System::nanoTime);
    }

    RegexRecheck(long validationBudgetMs, Listener listener, ValidationGate gate, RecheckRateLimiter limiter, LongSupplier nanoClock) {
        this.validationBudgetNanos = TimeUnit.MILLISECONDS.toNanos(validationBudgetMs);
        this.listener = listener == null ? Listener.NONE : listener;
        this.gate = gate;
        this.limiter = limiter;
        this.nanoClock = nanoClock;
    }

    /** Refills the JVM's quiet re-check tokens (tests only: no test sees the tokens another one used). */
    public static void resetSharedStateForTests() {
        RecheckRateLimiter.INSTANCE.reset();
    }

    /**
     * Calls the engine, and again while the result reports a regex timeout, as the decision rules allow.
     *
     * @param engineCall           one complete validation by the engine
     * @param requestDeadlineNanos when the request must be answered ({@link System#nanoTime()} scale), if any
     * @param version              for reporting
     * @param contextKey           for reporting
     */
    public Result run(Supplier<ValidationResult> engineCall, OptionalLong requestDeadlineNanos, SupportedFhirVersion version,
                      String contextKey) {
        long start = nanoClock.getAsLong();
        long deadline = start + validationBudgetNanos;
        if (requestDeadlineNanos.isPresent() && requestDeadlineNanos.getAsLong() - deadline < 0) {
            deadline = requestDeadlineNanos.getAsLong();
        }
        long attemptStart = start;
        ValidationResult result = gate.shared(engineCall);
        if (RegexTimeoutIssue.count(result) == 0) {
            return new Result(result, 1, OUTCOME_NONE, "");
        }
        long lastDuration = nanoClock.getAsLong() - attemptStart;
        if (!fits(deadline, lastDuration)) {
            return finish(version, contextKey, new Result(result, 1, OUTCOME_PERSISTENT, REASON_BUDGET));
        }
        listener.onRecheck(version, KIND_PLAIN);
        attemptStart = nanoClock.getAsLong();
        result = gate.shared(engineCall);
        int timeouts = RegexTimeoutIssue.count(result);
        if (timeouts == 0) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_RESOLVED, ""));
        }
        lastDuration = nanoClock.getAsLong() - attemptStart;
        if (timeouts > MAX_TIMEOUTS_FOR_QUIET) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_PERSISTENT, REASON_MANY_TIMEOUTS));
        }
        if (!limiter.available()) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_PERSISTENT, REASON_QUIET_LIMIT));
        }
        if (!fits(deadline, lastDuration)) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_PERSISTENT, REASON_BUDGET));
        }
        if (!limiter.tryTake()) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_PERSISTENT, REASON_QUIET_LIMIT));
        }
        long wait = Math.min(QUIET_WAIT_MAX_NANOS, deadline - nanoClock.getAsLong());
        Optional<ValidationResult> quiet = gate.exclusive(() -> {
            listener.onRecheck(version, KIND_QUIET);
            return engineCall.get();
        }, wait);
        if (quiet.isEmpty()) {
            return finish(version, contextKey, new Result(result, 2, OUTCOME_PERSISTENT, REASON_BUSY));
        }
        result = quiet.get();
        return finish(version, contextKey, RegexTimeoutIssue.count(result) == 0
                ? new Result(result, 3, OUTCOME_RESOLVED, "")
                : new Result(result, 3, OUTCOME_PERSISTENT, REASON_INPUT));
    }

    /** Whether one more attempt, allowing twice the last one's duration, ends before {@code deadline}. */
    private boolean fits(long deadline, long lastDuration) {
        return deadline - (nanoClock.getAsLong() + 2 * lastDuration) >= 0;
    }

    private Result finish(SupportedFhirVersion version, String contextKey, Result r) {
        listener.onOutcome(version, contextKey, r.outcome(), r.reason(), r.attempts());
        if (OUTCOME_PERSISTENT.equals(r.outcome())) {
            logger.warn("Regex timeout persisted for {}:{} after {} attempt(s) (reason {})", version, contextKey,
                    r.attempts(), r.reason());
        }
        return r;
    }
}
