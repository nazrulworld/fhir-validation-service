package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Optional;

/**
 * Mode {@code ecosystem} (spec 007 FR-016, US1): each code system that can't be decided locally is routed to the server
 * the official registry names for it ({@link RegistryResolver}), checked against the current allowlist on every call.
 * The context's own server, when set, replaces the registry's default server.
 */
public final class EcosystemRoutingTerminologySupport extends RoutedTerminologySupport {
    private final String contextServer;
    private final RegistryResolver resolver;

    public EcosystemRoutingTerminologySupport(FhirContext ctx, SupportedFhirVersion version, String contextServer,
                                              RegistryResolver resolver, TerminologyClients clients) {
        super(ctx, version, clients);
        this.contextServer = contextServer;
        this.resolver = resolver;
    }

    @Override
    protected Optional<String> route(String system) {
        return resolver.choose(version, system, contextServer, clients.allowlist().current());
    }

    @Override
    public String getName() {
        return "TerminologyEcosystem";
    }
}
