package nzi.fhir.validator.core.terminology;

/**
 * The terminology time budget of the validation running on this thread (spec 007 FR-019a, research R5). It counts only
 * time spent <em>waiting on terminology calls</em> (server calls and registry {@code /resolve} lookups): each call
 * {@link #charge}s its elapsed time, so local validation work, local terminology and cache hits never use it up. It
 * is never a wall-clock deadline.
 * <p>
 * HAPI validation runs synchronously on the validation worker thread (Principle I), so a thread-local is exactly
 * request-scoped. With no budget open (for example a direct chain call in a test), calls are bounded only by the
 * per-call timeout.
 */
public final class TerminologyBudget {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private static final class State {
        final long budgetMs;
        long spentMs;
        boolean degraded;
        boolean exhausted;

        State(long budgetMs) {
            this.budgetMs = Math.max(0, budgetMs);
        }
    }

    private TerminologyBudget() {
    }

    /**
     * Opens a budget for this thread's validation.
     *
     * @throws IllegalStateException if one is already open (validations don't nest)
     */
    public static void open(long budgetMs) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("a terminology budget is already open on this thread");
        }
        CURRENT.set(new State(budgetMs));
    }

    /** Ends this thread's budget; safe to call when none is open. */
    public static void close() {
        CURRENT.remove();
    }

    /** Whether a budget is open on this thread. */
    public static boolean isOpen() {
        return CURRENT.get() != null;
    }

    /** Budget minus the time charged so far; {@link Long#MAX_VALUE} when no budget is open. */
    public static long remainingMs() {
        State s = CURRENT.get();
        return s == null ? Long.MAX_VALUE : Math.max(0, s.budgetMs - s.spentMs);
    }

    /** Charges {@code elapsedMs} of terminology waiting to this thread's budget. */
    public static void charge(long elapsedMs) {
        State s = CURRENT.get();
        if (s != null && elapsedMs > 0) {
            s.spentMs += elapsedMs;
        }
    }

    /** Whether the budget is used up: no further call may be made for this validation. */
    public static boolean exhausted() {
        State s = CURRENT.get();
        return s != null && s.spentMs >= s.budgetMs;
    }

    /** Records that this validation produced a degraded ("not checked") terminology answer (research R6). */
    public static void markDegraded() {
        State s = CURRENT.get();
        if (s != null) {
            s.degraded = true;
        }
    }

    public static boolean degraded() {
        State s = CURRENT.get();
        return s != null && s.degraded;
    }

    /** Records that a call was skipped because the budget was used up (also degraded). */
    public static void markBudgetExhausted() {
        State s = CURRENT.get();
        if (s != null) {
            s.exhausted = true;
            s.degraded = true;
        }
    }

    public static boolean budgetExhausted() {
        State s = CURRENT.get();
        return s != null && s.exhausted;
    }
}
