package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Optional;

/** Mode {@code server} (spec 007 FR-015): every code that can't be decided locally goes to the context's one server. */
public final class ServerTerminologySupport extends RoutedTerminologySupport {
    private final String server;

    public ServerTerminologySupport(FhirContext ctx, SupportedFhirVersion version, TerminologyClients clients, String server) {
        super(ctx, version, clients);
        this.server = server;
    }

    @Override
    protected Optional<String> route(String system) {
        return Optional.of(server);
    }

    @Override
    public String getName() {
        return "TerminologyServer(" + TerminologyClients.server(server) + ")";
    }
}
