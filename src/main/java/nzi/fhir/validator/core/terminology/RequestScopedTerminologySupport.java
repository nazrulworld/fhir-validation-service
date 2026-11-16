package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.Optional;

/**
 * The one terminology module of every snapshot (spec 010 FR-001, research R1, R2): it routes with the request's
 * {@link TerminologyScope} instead of a setting fixed at build time, so a context holds definitions only and a setting
 * change never rebuilds it.
 * <ul>
 *     <li>Mode {@code none}: every code operation is declined (null), so the chain answers exactly as 1.0.0's chain
 *     without a terminology module. {@code ScopedValidationSupportChain} also skips this module then.</li>
 *     <li>{@code isCodeSystemSupported}/{@code isValueSetSupported} do not depend on the scope (they only say what is
 *     not decided locally), because HAPI's chain caches them per module.</li>
 *     <li>Otherwise the scope's {@link TerminologyRouter} chooses the server; a denied scope routes nowhere.</li>
 * </ul>
 */
public final class RequestScopedTerminologySupport extends RoutedTerminologySupport {
    public RequestScopedTerminologySupport(FhirContext ctx, SupportedFhirVersion version, TerminologyClients clients) {
        super(ctx, version, clients);
    }

    @Override
    protected Optional<String> route(String system) {
        return TerminologyScope.current().router().route(system);
    }

    @Override
    public CodeValidationResult validateCode(ValidationSupportContext vsc, ConceptValidationOptions options, String system,
                                             String code, String display, String valueSetUrl) {
        return TerminologyScope.current().offline() ? null
                : super.validateCode(vsc, options, system, code, display, valueSetUrl);
    }

    @Override
    public CodeValidationResult validateCodeInValueSet(ValidationSupportContext vsc, ConceptValidationOptions options,
                                                       String system, String code, String display, IBaseResource valueSet) {
        return TerminologyScope.current().offline() ? null
                : super.validateCodeInValueSet(vsc, options, system, code, display, valueSet);
    }

    @Override
    public LookupCodeResult lookupCode(ValidationSupportContext vsc, LookupCodeRequest request) {
        return TerminologyScope.current().offline() ? null : super.lookupCode(vsc, request);
    }

    @Override
    public String getName() {
        return "TerminologyRequestScoped";
    }
}
