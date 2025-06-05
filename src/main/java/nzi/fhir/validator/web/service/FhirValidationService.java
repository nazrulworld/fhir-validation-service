package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.model.IGPackageIdentity;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseMetaType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import nzi.fhir.validator.web.enums.SupportedContentType;
import nzi.fhir.validator.model.ValidationRequestContext;
import nzi.fhir.validator.model.ValidationRequestOptions;

import java.util.HashMap;
import java.util.Objects;

/**
 * @author Md Nazrul Islam
 */
public class FhirValidationService {

    private static final Logger logger = LogManager.getLogger(FhirValidationService.class);
    private final static HashMap<IGPackageIdentity, FhirValidationService> validationServicesStorage;
    private final Vertx vertx; // Mandatory
    private final FhirContext fhirContext; // Mandatory
    private final FhirValidator validator; // Mandatory Cached validator
    private final IParser fhirJsonParser;
    private final IParser fhirXMLParser;
    static {
        validationServicesStorage = new HashMap<>();
    }
    private FhirValidationService(Vertx vertx, FhirContext fhirContext, FhirValidator validator) {
        this.vertx = vertx;
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.fhirXMLParser = fhirContext.newXmlParser();
    }

    public static Future<FhirValidationService> create(Vertx vertx, FhirContext fhirContext, ProfileService profileService){
        // do testng test
        IGPackageIdentity coreIgPackageIdentity = new IGPackageIdentity(IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME, fhirContext.getVersion().getVersion().getFhirVersionString(), SupportedFhirVersion.valueOf(fhirContext.getVersion().getVersion().name()));
        return create(vertx, coreIgPackageIdentity, null, profileService);
    }
    public static Future<FhirValidationService> create(Vertx vertx, IGPackageIdentity igPackageIdentity,
                                                       IgPackageService igPackageService, ProfileService profileService) {

        Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        Objects.requireNonNull(igPackageIdentity, "IGPackageIdentity cannot be null");
        Objects.requireNonNull(profileService, "ProfileService cannot be null");

        FhirContext fhirContext = FhirContextLoader.getInstance().getContext(igPackageIdentity.getFhirVersion());

        return createValidator(igPackageIdentity, igPackageService, profileService)
                .map(validator -> new FhirValidationService(vertx, fhirContext, validator));
    }

    private static Future<FhirValidator> createValidator(IGPackageIdentity igPackageIdentity, IgPackageService igPackageService, ProfileService profileService) {
        return Future.future(promise -> {
            try {
                FhirContext fhirContext = FhirContextLoader.getInstance().getContext(igPackageIdentity.getFhirVersion());
                ValidationSupportChain validationSupportChain = new ValidationSupportChain();
                validationSupportChain.addValidationSupport(new CustomProfileValidationSupport(fhirContext, profileService));

                // Create base validation supports
                DefaultProfileValidationSupport defaultSupport = new DefaultProfileValidationSupport(fhirContext);
                InMemoryTerminologyServerValidationSupport inMemoryTerminology = new InMemoryTerminologyServerValidationSupport(fhirContext);
                CommonCodeSystemsTerminologyService commonTerminology = new CommonCodeSystemsTerminologyService(fhirContext);
                
                validationSupportChain.addValidationSupport(defaultSupport);
                validationSupportChain.addValidationSupport(inMemoryTerminology);
                validationSupportChain.addValidationSupport(commonTerminology);

                if (!igPackageIdentity.getName().equals(IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME)) {
                    Validate.notNull(igPackageService, "IG service must not be null");
                    
                    CustomNpmPackageValidationSupport.getValidationSupport(igPackageIdentity, igPackageService)
                        .onSuccess(npmSupport -> {
                            if (npmSupport != null) {
                                validationSupportChain.addValidationSupport(npmSupport);
                            } else {
                                logger.warn("No NPM package validation support found for IG package: {}", 
                                    igPackageIdentity.getName());
                            }
                            
                            // Create and configure validator after NPM support is handled
                            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
                            FhirValidator validator = fhirContext.newValidator();
                            validator.registerValidatorModule(instanceValidator);
                            promise.complete(validator);
                        })
                        .onFailure(err -> {
                            logger.error("Failed to get NPM package validation support", err);
                            promise.fail(err);
                        });
                } else {
                    // If no NPM package is needed, create validator directly
                    FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
                    FhirValidator validator = fhirContext.newValidator();
                    validator.registerValidatorModule(instanceValidator);
                    promise.complete(validator);
                }
            } catch (Exception e) {
                logger.error("Error creating FHIR validator", e);
                promise.fail(e);
            }
        });
    }

    public Future<JsonObject> validate(String content, ValidationRequestContext validationRequestContext) {
        return vertx.executeBlocking(() -> {
            try {
                IBaseResource parsedResource;
                // Parse the resource
                if (validationRequestContext.getContentType() == SupportedContentType.JSON ||
                        validationRequestContext.getContentType() == SupportedContentType.FHIR_JSON) {
                    parsedResource = fhirJsonParser.parseResource(content);
                } else {
                    parsedResource = fhirXMLParser.parseResource(content);
                }
                // Add profiles if specified in options
                if (!validationRequestContext.getValidationOptions().getProfilesToValidate().isEmpty() &&
                        parsedResource instanceof IAnyResource) {
                    addProfilesToResource(parsedResource, validationRequestContext.getValidationOptions());
                }
                // Use the cached validator
                ValidationResult result = validator.validateWithResult(parsedResource);
                return convertToJson(result);
            } catch (Exception e) {
                logger.error("Validation failed: {}", e.getMessage(), e);
                throw new RuntimeException("Validation failed", e);
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

    private void addProfilesToResource(IBaseResource resource, ValidationRequestOptions options) {
        try {
            // Cast to IAnyResource to access meta-information
            IAnyResource anyResource = (IAnyResource) resource;

            // Get meta-information
            IBaseMetaType meta = anyResource.getMeta();

            // If meta is null, create a new meta
            if (meta == null) {
                meta = (IBaseMetaType) fhirContext.getResourceDefinition("Meta").newInstance();
                java.lang.reflect.Method setMetaMethod = anyResource.getClass().getMethod("setMeta", IBaseMetaType.class);
                setMetaMethod.invoke(anyResource, meta);
            }

            // Add profiles
            for (String profile : options.getProfilesToValidate()) {
                meta.addProfile(profile.trim());
            }
        } catch (Exception e) {
            logger.error("Unable to set meta information on resource: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Resource type does not support meta information", e);
        }
    }

    public static FhirValidationService get(IGPackageIdentity igPackageIdentity) {
        return validationServicesStorage.get(igPackageIdentity);
    }
    public static void put(IGPackageIdentity igPackageIdentity, FhirValidationService validationService) {
        validationServicesStorage.put(igPackageIdentity, validationService);
    }
    public static void remove(IGPackageIdentity igPackageIdentity) {
        validationServicesStorage.remove(igPackageIdentity);
    }
    public static void clear() {
        validationServicesStorage.clear();
    }
    public static int size() {
        return validationServicesStorage.size();
    }
}