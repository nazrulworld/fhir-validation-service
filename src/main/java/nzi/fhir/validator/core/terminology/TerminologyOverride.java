package nzi.fhir.validator.core.terminology;

/**
 * The terminology choice of one validate request (spec 010 FR-005, data-model §3): the {@code txMode} and {@code tx}
 * query parameters, both optional. Parsed and checked by {@code ValidationRequestOptions}; this type only holds the
 * result.
 *
 * @param mode   {@code txMode}, or {@code null} when absent
 * @param server {@code tx} as a normalised base URL ({@link TerminologySettings#normaliseUrl}), or {@code null}
 */
public record TerminologyOverride(TerminologyMode mode, String server) {
    /** Neither parameter was given: the context's default applies. */
    public static final TerminologyOverride ABSENT = new TerminologyOverride(null, null);

    public TerminologyOverride {
        if (mode == TerminologyMode.NONE && server != null) {
            throw new IllegalArgumentException("txMode=none takes no tx");
        }
    }

    /** Whether either parameter was given (the effective setting then comes from the request). */
    public boolean present() {
        return mode != null || server != null;
    }
}
