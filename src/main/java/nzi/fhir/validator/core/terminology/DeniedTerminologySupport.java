package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Optional;

/**
 * A context whose terminology setting names a server that is not (or no longer) allowed (spec 007 US4 scenario 4,
 * FR-010 b): the build fails closed for terminology only. Codes that can be decided locally still are; every other one
 * is answered "No permitted terminology server for {system}; code was not checked" (a warning), with no network call.
 */
public final class DeniedTerminologySupport extends RoutedTerminologySupport {
    public DeniedTerminologySupport(FhirContext ctx, SupportedFhirVersion version) {
        super(ctx, version, TerminologyClients.NONE);
    }

    @Override
    protected Optional<String> route(String system) {
        return Optional.empty();
    }

    @Override
    public String getName() {
        return "TerminologyDenied";
    }
}
