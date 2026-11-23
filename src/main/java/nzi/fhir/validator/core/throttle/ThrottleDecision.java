package nzi.fhir.validator.core.throttle;

/**
 * The outcome of one rate-limit check (spec 014, data-model §3).
 *
 * @param admitted          whether the request may be served
 * @param limited           whether any level has a cap; {@code false} = no {@code RateLimit-*} headers
 * @param scope             the level reported: for a refusal, the cap that refused; for an admission, the level with
 *                          the least remaining (the key on a tie)
 * @param reason            why a request was refused ({@code null} when admitted)
 * @param limit             the reported level's cap
 * @param remaining         what is left at the reported level after this request (at least 0)
 * @param resetSeconds      seconds until the reported level's allowance next grows
 * @param retryAfterSeconds for a refusal, seconds until a retry can be admitted (at least 1); 0 when admitted
 */
public record ThrottleDecision(boolean admitted, boolean limited, Scope scope, Reason reason, int limit, int remaining,
                               int resetSeconds, int retryAfterSeconds) {

    public enum Scope {
        KEY("key"), ORGANISATION("organisation");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        /** As in the problem's {@code scope} member and the metric tag. */
        public String label() {
            return label;
        }
    }

    public enum Reason {
        /** The windowed count reached the cap. */
        CAP("cap"),
        /** Research R4: too many admissions not yet visible to the other instances. */
        UNCONFIRMED("unconfirmed");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final ThrottleDecision UNLIMITED = new ThrottleDecision(true, false, null, null, 0, 0, 0, 0);

    /** Admitted, and no level has a cap (nothing counted, no headers). */
    public static ThrottleDecision unlimited() {
        return UNLIMITED;
    }
}
