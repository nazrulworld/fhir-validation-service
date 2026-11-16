package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Optional;

/**
 * Chooses the terminology server for a code system during one request (spec 010 research R2). Replaces the per-build
 * modules of spec 007 ({@code ServerTerminologySupport}, {@code EcosystemRoutingTerminologySupport},
 * {@code DeniedTerminologySupport}): the request's {@link TerminologyScope} holds one router.
 */
public sealed interface TerminologyRouter {
    /**
     * The server for codes of {@code system}, or empty when none may be contacted (the code is then reported as not
     * checked).
     */
    Optional<String> route(String system);

    /** Mode {@code none}: never asked (the module declines every call first). */
    TerminologyRouter NONE = new Fixed(Optional.empty());

    /** A context default that is no longer allowed (spec 007 US4-4, spec 010 FR-011): fails closed, no call. */
    TerminologyRouter DENIED = new Fixed(Optional.empty());

    /** Mode {@code server} (spec 007 FR-015): every code that can't be decided locally goes to {@code url}. */
    static TerminologyRouter server(String url) {
        return new Fixed(Optional.of(url));
    }

    /**
     * Mode {@code ecosystem} (spec 007 FR-016): the server the official registry names for each code system, checked
     * against the current allowlist on every call; {@code defaultServer}, when set, replaces the registry's default.
     */
    static TerminologyRouter ecosystem(SupportedFhirVersion version, String defaultServer, RegistryResolver resolver,
                                       TxServerAllowlistSource allowlist) {
        return new Ecosystem(version, defaultServer, resolver, allowlist);
    }

    /** The router of an effective setting. */
    static TerminologyRouter of(EffectiveTerminology effective, SupportedFhirVersion version, RegistryResolver resolver,
                                TxServerAllowlistSource allowlist) {
        if (effective.denied()) {
            return DENIED;
        }
        TerminologySettings s = effective.settings();
        return switch (s.mode()) {
            case NONE -> NONE;
            case SERVER -> server(s.server());
            case ECOSYSTEM -> ecosystem(version, s.server(), resolver, allowlist);
        };
    }

    record Fixed(Optional<String> server) implements TerminologyRouter {
        @Override
        public Optional<String> route(String system) {
            return server;
        }
    }

    record Ecosystem(SupportedFhirVersion version, String defaultServer, RegistryResolver resolver,
                     TxServerAllowlistSource allowlist) implements TerminologyRouter {
        @Override
        public Optional<String> route(String system) {
            return resolver.choose(version, system, defaultServer, allowlist.current());
        }
    }
}
