package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

public class CustomValidationSupport extends DefaultProfileValidationSupport {
    private static final Logger logger = LogManager.getLogger(CustomValidationSupport.class);
    private final ProfileService profileService;

    public CustomValidationSupport(FhirContext ctx, ProfileService profileService) {
        super(ctx);
        this.profileService = profileService;
    }

    @Override
    public <T extends IBaseResource> T fetchResource(Class<T> resourceType, String theUrl) {
        try {
            IBaseResource profile = profileService.getProfile(theUrl).result();
            if (profile != null) {
                logger.debug("Fetched profile from ProfileService: {}", theUrl);
                if (resourceType.isInstance(profile)) {
                    return resourceType.cast(profile);
                }
            }
            logger.debug("Profile not found or type mismatch, falling back to default support: {}", theUrl);
            return super.fetchResource(resourceType, theUrl);
        } catch (Exception e) {
            logger.error("Failed to fetch profile {}: {}", theUrl, e.getMessage(), e);
            return null;
        }
    }
}
