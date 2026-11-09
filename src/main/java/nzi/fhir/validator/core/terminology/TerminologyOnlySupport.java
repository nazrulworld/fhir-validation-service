package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Exposes only the terminology operations of a module (spec 007 FR-014, research R2): whether a code system or value
 * set is supported, code validation (also in a value set) and code lookup. Every other method keeps the interface
 * default ({@code null}/empty), so a remote module can never supply StructureDefinitions, ValueSets or CodeSystems:
 * definitions come only from packages (spec 003 FR-002). A forwarding wrapper rather than a subclass, so a method HAPI
 * adds later is never inherited silently.
 */
public final class TerminologyOnlySupport implements IValidationSupport {
    private final IValidationSupport delegate;

    public TerminologyOnlySupport(IValidationSupport delegate) {
        this.delegate = delegate;
    }

    @Override
    public FhirContext getFhirContext() {
        return delegate.getFhirContext();
    }

    @Override
    public String getName() {
        return "TerminologyOnly(" + delegate.getName() + ")";
    }

    @Override
    public boolean isCodeSystemSupported(ValidationSupportContext ctx, String system) {
        return delegate.isCodeSystemSupported(ctx, system);
    }

    @Override
    public boolean isValueSetSupported(ValidationSupportContext ctx, String valueSetUrl) {
        return delegate.isValueSetSupported(ctx, valueSetUrl);
    }

    @Override
    public CodeValidationResult validateCode(ValidationSupportContext ctx, ConceptValidationOptions options, String system,
                                             String code, String display, String valueSetUrl) {
        return delegate.validateCode(ctx, options, system, code, display, valueSetUrl);
    }

    @Override
    public CodeValidationResult validateCodeInValueSet(ValidationSupportContext ctx, ConceptValidationOptions options,
                                                       String system, String code, String display, IBaseResource valueSet) {
        return delegate.validateCodeInValueSet(ctx, options, system, code, display, valueSet);
    }

    @Override
    public LookupCodeResult lookupCode(ValidationSupportContext ctx, LookupCodeRequest request) {
        return delegate.lookupCode(ctx, request);
    }
}
