package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupport;

/**
 * @author Md Nazrul Islam
 */
public class CustomProfileValidationSupport extends BaseValidationSupport {
    private static final Logger logger = LogManager.getLogger(CustomProfileValidationSupport.class);
    private final ProfileService profileService;

    public CustomProfileValidationSupport(FhirContext ctx, ProfileService profileService) {
        super(ctx);
        this.profileService = profileService;
    }

    @Override
    public IBaseResource fetchStructureDefinition(String url) {
        try {
            IBaseResource profile = profileService.getProfile(url).result();
            if (profile != null) {
                logger.debug("Fetched profile from ProfileService: {}", url);
                return profile;
            }
            logger.debug("Profile not found, will try next support in chain: {}", url);
            return null;
        } catch (Exception e) {
            logger.error("Failed to fetch profile {}: {}", url, e.getMessage(), e);
            return null;
        }
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
}