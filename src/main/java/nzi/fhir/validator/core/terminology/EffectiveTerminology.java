package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.web.ProblemException;

import java.util.Objects;

/**
 * The terminology setting one validation request uses from start to end (spec 010 FR-003, FR-006, data-model §4).
 *
 * @param settings the mode and server used
 * @param source   where it came from: the request ({@code tx}/{@code txMode} given) or the context's default
 * @param denied   the context's default names a server that is no longer allowed: codes that need a server are
 *                 reported as not checked, without any call (FR-011); only possible with {@link Source#CONTEXT}
 */
public record EffectiveTerminology(TerminologySettings settings, Source source, boolean denied) {
    public enum Source {
        REQUEST, CONTEXT;

        /** The value of the {@code X-Tx-Source} header and the usage record. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Offline, from the context: warm-up, tests, and the base context without parameters. */
    public static final EffectiveTerminology NONE = new EffectiveTerminology(TerminologySettings.NONE, Source.CONTEXT, false);

    public EffectiveTerminology {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(source, "source");
        if (denied && source != Source.CONTEXT) {
            throw new IllegalArgumentException("only a context default can be denied");
        }
    }

    /**
     * The resolution table of spec 010 FR-006 (data-model §4). Pure: no allowlist check here.
     *
     * @param contextDefault the context's configured setting ({@link TerminologySettings#NONE} for the base context and a
     *                       context with no setting)
     * @throws ProblemException 400 {@code invalid-request} for {@code txMode=server} without a server anywhere, and for
     *                          {@code txMode=none} with {@code tx}
     */
    public static EffectiveTerminology resolve(TerminologySettings contextDefault, TerminologyOverride override) {
        TerminologySettings d = contextDefault == null ? TerminologySettings.NONE : contextDefault;
        if (override == null || !override.present()) {
            return new EffectiveTerminology(d, Source.CONTEXT, false);
        }
        String url = override.server();
        TerminologyMode mode = override.mode();
        TerminologySettings settings;
        if (mode == null) {
            TerminologyMode m = d.mode() == TerminologyMode.SERVER || d.mode() == TerminologyMode.ECOSYSTEM
                    ? d.mode() : TerminologyMode.SERVER;
            settings = new TerminologySettings(m, url);
        } else {
            settings = switch (mode) {
                case NONE -> {
                    if (url != null) {
                        throw invalid("tx is not allowed with txMode=none");
                    }
                    yield TerminologySettings.NONE;
                }
                case SERVER -> {
                    String server = url != null ? url : d.server();
                    if (server == null) {
                        throw invalid("txMode=server needs a tx server; this context has none");
                    }
                    yield new TerminologySettings(TerminologyMode.SERVER, server);
                }
                case ECOSYSTEM -> new TerminologySettings(TerminologyMode.ECOSYSTEM,
                        url != null ? url : d.mode() == TerminologyMode.ECOSYSTEM ? d.server() : null);
            };
        }
        return new EffectiveTerminology(settings, Source.REQUEST, false);
    }

    /** The same setting, marked as a context default that is no longer allowed. */
    public EffectiveTerminology asDenied() {
        return new EffectiveTerminology(settings, Source.CONTEXT, true);
    }

    /**
     * The terminology scope (spec 010 FR-012, data-model §5): part of every code-cache key, so requests never share a
     * result unless they used the same server(s).
     */
    public String scopeId() {
        if (denied) {
            return "denied";
        }
        return switch (settings.mode()) {
            case NONE -> "none";
            case SERVER -> "server:" + settings.server();
            case ECOSYSTEM -> "ecosystem:" + (settings.server() == null ? "-" : settings.server());
        };
    }

    /**
     * The {@code X-Tx-Server} value (spec 010 FR-010): the server used ({@code server}); for {@code ecosystem} the
     * request's or context's server, never the registry's own default; {@code null} otherwise.
     */
    public String reportedServer() {
        return settings.mode() == TerminologyMode.NONE ? null : settings.server();
    }

    private static ProblemException invalid(String detail) {
        return new ProblemException(400, "invalid-request", detail);
    }
}
