package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.model.ValidatorIdentity;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.model.IGPackageIdentity;
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
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.model.ValidationRequestContext;
import nzi.fhir.validator.core.model.ValidationRequestOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * @author Md Nazrul Islam
 */
public class FhirValidationService {

    private static final Logger logger = LogManager.getLogger(FhirValidationService.class);
    private final static HashMap<ValidatorIdentity, FhirValidationService> validationServicesStorage;
    private final ValidatorIdentity id;
    private final Vertx vertx; // Mandatory
    private final FhirContext fhirContext; // Mandatory
    private final FhirValidator validator; // Mandatory Cached validator
    private final IParser fhirJsonParser;
    private final IParser fhirXMLParser;
    static {
        validationServicesStorage = new HashMap<>();
    }
    private FhirValidationService(Vertx vertx, FhirContext fhirContext, FhirValidator validator){
        this(vertx, fhirContext, validator, ValidatorIdentity.createFromFhirVersion(SupportedFhirVersion.fromVersionNumber(fhirContext.getVersion().getVersion().getFhirVersionString())));
    }
    private FhirValidationService(Vertx vertx, FhirContext fhirContext, FhirValidator validator, ValidatorIdentity validatorIdentity) {
        this.id = validatorIdentity;
        this.vertx = vertx;
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.fhirXMLParser = fhirContext.newXmlParser();
    }

    public static Future<FhirValidationService> create(Vertx vertx, SupportedFhirVersion fhirVersion, ProfileService profileService){
        // do testng test
        return create(vertx, fhirVersion, null, profileService);
    }
    public static Future<FhirValidationService> create(Vertx vertx, SupportedFhirVersion fhirVersion, IgPackageService igPackageService, ProfileService profileService) {
        ValidatorIdentity validatorIdentity = ValidatorIdentity.createFromFhirVersion(fhirVersion);
        return create(vertx, validatorIdentity, igPackageService, profileService);
    }
    public static Future<FhirValidationService> create(Vertx vertx, ValidatorIdentity validatorIdentity, IgPackageService igPackageService, ProfileService profileService) {

        return create(vertx, validatorIdentity, igPackageService, profileService, null);
    }
    public static Future<FhirValidationService> create(
            Vertx vertx, ValidatorIdentity validatorIdentity, IgPackageService igPackageService, ProfileService profileService, IGPackageIdentity igPackageIdentity) {

        Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        Objects.requireNonNull(validatorIdentity, "ValidatorIdentity cannot be null");
        Objects.requireNonNull(profileService, "ProfileService cannot be null");

        FhirContext fhirContext = FhirContextLoader.getInstance().getContext(validatorIdentity.getFhirVersion());

        return createValidator(vertx, validatorIdentity ,igPackageService, profileService, igPackageIdentity)
                .map(validator -> {
                    FhirValidationService validationService = new FhirValidationService(vertx, fhirContext, validator, validatorIdentity);
                    put(validatorIdentity, validationService);
                    return validationService;
        });
    }
    private static Future<FhirValidator> createValidator(Vertx vertx,  ValidatorIdentity validatorIdentity, IgPackageService igPackageService, ProfileService profileService) {
        return createValidator(vertx, validatorIdentity, igPackageService, profileService, null);
    }
    private static Future<FhirValidator> createValidator(Vertx vertx, ValidatorIdentity validatorIdentity, IgPackageService igPackageService, ProfileService profileService, IGPackageIdentity igPackageIdentity) {
        return Future.future(promise -> {
            vertx.executeBlocking(blockingPromise -> {
                try {
                    FhirContext fhirContext = FhirContextLoader.getInstance().getContext(validatorIdentity.getFhirVersion());
                    ValidationSupportChain validationSupportChain = new ValidationSupportChain();
                    // Create base validation supports
                    DefaultProfileValidationSupport defaultSupport = new DefaultProfileValidationSupport(fhirContext);
                    InMemoryTerminologyServerValidationSupport inMemoryTerminology = new InMemoryTerminologyServerValidationSupport(fhirContext);
                    CommonCodeSystemsTerminologyService commonTerminology = new CommonCodeSystemsTerminologyService(fhirContext);
                    
                    validationSupportChain.addValidationSupport(defaultSupport);
                    validationSupportChain.addValidationSupport(inMemoryTerminology);
                    validationSupportChain.addValidationSupport(commonTerminology);
                    validationSupportChain.addValidationSupport(new CustomProfileValidationSupport(fhirContext, profileService));
                    
                    CustomNpmPackageValidationSupport npmPackageValidationSupport = CustomNpmPackageValidationSupport.getValidationSupport(validatorIdentity, igPackageService);
                    validationSupportChain.addValidationSupport(npmPackageValidationSupport);
                    FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
                    FhirValidator validator = fhirContext.newValidator();
                    validator.registerValidatorModule(instanceValidator);

                    if (igPackageIdentity != null && !igPackageIdentity.getName().equals(IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME)) {
                        Validate.notNull(igPackageService, "IG service must not be null");

                        if (CustomNpmPackageValidationSupport.isValidClassPath(igPackageIdentity.asClassPath())) {
                            try {
                                npmPackageValidationSupport.loadPackageFromClasspath(igPackageIdentity.asClassPath());
                                blockingPromise.complete(validator);
                            } catch (Exception e) {
                                logger.error("Failed to load IG package: {}", e.getMessage(), e);
                                blockingPromise.fail(e);
                            }
                        } else {
                            // Handle the async database loading properly
                            npmPackageValidationSupport.loadIgPackageFromDatabase(
                                    igPackageIdentity.getName(),
                                    igPackageIdentity.getVersion())
                                .onSuccess(v -> blockingPromise.complete(validator))
                                .onFailure(e -> {
                                    logger.error("Failed to load IG package from database: {}", e.getMessage(), e);
                                    blockingPromise.fail(e);
                                });
                            // Important: Return here to prevent double completion
                            return;
                        }
                    } else {
                        blockingPromise.complete(validator);
                    }
                } catch (Exception e) {
                    blockingPromise.fail(e);
                }
            }, promise);
        });
    }

    public Future<JsonObject> validate(String content, ValidationRequestContext validationRequestContext) {
        return vertx.executeBlocking(promise -> {
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
                if (validationRequestContext.getValidationOptions().getProfilesToValidate() != null && 
                    !validationRequestContext.getValidationOptions().getProfilesToValidate().isEmpty() &&
                    parsedResource instanceof IAnyResource) {
                    addProfilesToResource(parsedResource, validationRequestContext.getValidationOptions());
                }
                // Use the cached validator
                ValidationResult result = validator.validateWithResult(parsedResource);
                promise.complete(convertToJson(result));
            } catch (ca.uhn.fhir.parser.DataFormatException e) {
                logger.debug("Invalid FHIR formatted data: {}", e.getMessage(), e);
                JsonObject error =  new JsonObject()
                        .put("valid", false)
                        .put("messages", new JsonArray().add(new JsonObject().put("severity", "error").put("message", e.getMessage()).put("location", "")));
                promise.complete(error);
            }
        });
    }

    public Future<Void> addNpmIgPackage(IGPackageIdentity igPackageIdentity) {
        // BBC
        CustomNpmPackageValidationSupport npmPackageValidationSupport = CustomNpmPackageValidationSupport.getValidationSupport(id);
        if (npmPackageValidationSupport == null) {
            return Future.failedFuture("Unable to find validation support for validator: " + id.toString());
        }
        return npmPackageValidationSupport.loadIgPackageFromDatabase(igPackageIdentity.getName(), igPackageIdentity.getVersion());
    }

    public List<IGPackageIdentity> getIncludedIgPackagesListForNpmPackageValidation(){
        CustomNpmPackageValidationSupport npmPackageValidationSupport = CustomNpmPackageValidationSupport.getValidationSupport(id);
        if (npmPackageValidationSupport == null) {
            logger.info("Unable to find validation support for validator: {}", id.toString());
            return new ArrayList<>();
        }
        return npmPackageValidationSupport.getIncludedIgPackages();
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

    public static FhirValidationService get(ValidatorIdentity validatorIdentity) {
        return validationServicesStorage.get(validatorIdentity);
    }
    public static void put(ValidatorIdentity validatorIdentity, FhirValidationService validationService) {
        if (validationServicesStorage.containsKey(validatorIdentity)) {
            remove(validatorIdentity);
        }
        validationServicesStorage.put(validatorIdentity, validationService);
    }
    public static void remove(ValidatorIdentity validatorIdentity) {
        validationServicesStorage.remove(validatorIdentity);
    }
    public static void clear() {
        validationServicesStorage.clear();
    }
    public static int size() {
        return validationServicesStorage.size();
    }
}