package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.validator.ProfileStore;
import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;

/**
 * Serves custom profiles from the in-memory {@link ProfileStore}. Purely synchronous: no database access and no
 * futures during validation (fixes B1, where a pending future's {@code result()} returned {@code null}).
 *
 * @author Md Nazrul Islam
 */
public class CustomProfileValidationSupport extends BaseValidationSupport {
    private final ProfileStore profileStore;

    public CustomProfileValidationSupport(FhirContext ctx, ProfileStore profileStore) {
        super(ctx);
        this.profileStore = profileStore;
    }

    @Override
    public IBaseResource fetchStructureDefinition(String url) {
        return profileStore.get(url);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
        return (List<T>) profileStore.all();
    }

    @Override
    public boolean isCodeSystemSupported(ValidationSupportContext theValidationSupportContext, String theSystem) {
        return false; // Let the terminology service handle code systems
    }

    @Override
    public boolean isValueSetSupported(ValidationSupportContext theValidationSupportContext, String theValueSetUrl) {
        return false; // Let the terminology service handle value sets
    }

    @Override
    public CodeValidationResult validateCode(ValidationSupportContext theValidationSupportContext, ConceptValidationOptions theOptions, String theCodeSystem, String theCode, String theDisplay, String theValueSetUrl) {
        return null; // Let the terminology service handle code validation
    }

    @Override
    public LookupCodeResult lookupCode(ValidationSupportContext theValidationSupportContext, String theSystem, String theCode) {
        return null; // Let the terminology service handle code lookups
    }

    @Override
    public String getName() {
        return "CustomProfileValidationSupport";
    }
}
