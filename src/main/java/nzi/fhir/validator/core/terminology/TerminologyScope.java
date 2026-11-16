package nzi.fhir.validator.core.terminology;

import java.util.Objects;

/**
 * The terminology setting of the validation running on this thread (spec 010 FR-003, research R1, data-model §5).
 * HAPI validates synchronously on the validation worker (constitution I), so a thread-local is exactly
 * request-scoped, like {@link TerminologyBudget}. With no scope open, terminology behaves as mode {@code none}:
 * nothing outside a validation can reach a server.
 */
public final class TerminologyScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /** An open scope. */
    public record Scope(EffectiveTerminology effective, TerminologyRouter router) {
        public Scope {
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(router, "router");
        }

        public String scopeId() {
            return effective.scopeId();
        }

        /** Mode {@code none}: the request module declines everything (1.0.0 behaviour without a module). */
        public boolean offline() {
            return !effective.denied() && effective.settings().mode() == TerminologyMode.NONE;
        }
    }

    private static final Scope OFFLINE = new Scope(EffectiveTerminology.NONE, TerminologyRouter.NONE);

    private TerminologyScope() {
    }

    /**
     * Opens a scope for this thread's validation.
     *
     * @throws IllegalStateException if one is already open (validations don't nest)
     */
    public static void open(EffectiveTerminology effective, TerminologyRouter router) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("a terminology scope is already open on this thread");
        }
        CURRENT.set(new Scope(effective, router));
    }

    /** Opens an offline scope (mode {@code none}): warm-up and tests (spec 010 FR-004). */
    public static void openNone() {
        open(EffectiveTerminology.NONE, TerminologyRouter.NONE);
    }

    /** Ends this thread's scope; safe to call when none is open. */
    public static void close() {
        CURRENT.remove();
    }

    public static boolean isOpen() {
        return CURRENT.get() != null;
    }

    /** This thread's scope, or the offline scope when none is open. */
    public static Scope current() {
        Scope s = CURRENT.get();
        return s == null ? OFFLINE : s;
    }
}
