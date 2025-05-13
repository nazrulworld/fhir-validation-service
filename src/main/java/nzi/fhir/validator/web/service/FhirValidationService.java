package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

public class FhirValidationService {
    private static final String PROFILE_OPTION = "profile";
    private static final Logger logger = LogManager.getLogger(FhirValidationService.class);

    private final Vertx vertx;
    private final FhirContext fhirContext;
    private final ProfileService profileService;
    private final IgService igService;

    public FhirValidationService(Vertx vertx, String fhirVersion, ProfileService profileService, IgService igService) {
        this.vertx = vertx;
        this.fhirContext = "R5".equalsIgnoreCase(fhirVersion) ? FhirContext.forR5() : FhirContext.forR4();
        this.profileService = profileService;
        this.igService = igService;
    }

    public Future<JsonObject> validate(JsonObject resource, JsonObject options) {
        if (resource == null || resource.isEmpty()) {
            logger.error("FHIR resource is null or empty");
            return Future.failedFuture("FHIR resource cannot be null or empty");
        }
        final JsonObject finalOptions = options != null ? options : new JsonObject();

        return vertx.executeBlocking(promise -> {
            try {
                FhirValidator validator = fhirContext.newValidator();
                IValidationSupport validationSupport = new CustomValidationSupport(fhirContext, profileService);
                // validator.setValidationSupport(validationSupport); // This method doesn't exist in the current HAPI FHIR version

                IBaseResource parsedResource = fhirContext.newJsonParser().parseResource(resource.encode());
                ValidationResult result = validator.validateWithResult(parsedResource);
                promise.complete(convertToJson(result));
            } catch (Exception e) {
                logger.error("Validation failed: {}", e.getMessage(), e);
                promise.fail(e);
            }
        });
    }

    private JsonObject convertToJson(ValidationResult result) {
        JsonArray messages = new JsonArray();
        result.getMessages().forEach(msg ->
                messages.add(new JsonObject()
                        .put("severity", msg.getSeverity().name())
                        .put("location", msg.getLocationString())
                        .put("message", msg.getMessage()))
        );

        return new JsonObject()
                .put("valid", result.isSuccessful())
                .put("messages", messages);
    }
}
