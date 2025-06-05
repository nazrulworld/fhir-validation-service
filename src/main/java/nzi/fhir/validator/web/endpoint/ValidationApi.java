package nzi.fhir.validator.web.endpoint;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.model.IGPackageIdentity;
import nzi.fhir.validator.model.ValidationRequestContext;
import nzi.fhir.validator.web.service.FhirContextLoader;
import nzi.fhir.validator.web.service.FhirValidationService;
import nzi.fhir.validator.web.service.IgPackageService;
import nzi.fhir.validator.web.service.ProfileService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;

/**
 * @author Md Nazrul Islam
 */
public class ValidationApi {
    private static final Logger logger = LogManager.getLogger(ValidationApi.class);
    private  Vertx vertx;
    private  Pool pgPool;

    public static Future<ValidationApi> create(Vertx vertx, Pool pgPool) {
        return Future.future(promise -> {
            FhirContextLoader fhirContextLoader = FhirContextLoader.getInstance();

            // Create FhirContext instances for different FHIR versions
            FhirContext fhirContextR4 = fhirContextLoader.getContext(SupportedFhirVersion.R4);
            FhirContext fhirContextR4B = fhirContextLoader.getContext(SupportedFhirVersion.R4B);
            FhirContext fhirContextR5 = fhirContextLoader.getContext(SupportedFhirVersion.R5);

            // Create profile services for different FHIR versions
            ProfileService profileServiceR4 = ProfileService.create(vertx, fhirContextR4, pgPool);
            ProfileService profileServiceR4B = ProfileService.create(vertx, fhirContextR4B, pgPool);
            ProfileService profileServiceR5 = ProfileService.create(vertx, fhirContextR5, pgPool);

            // Create IG services for different FHIR versions
            IgPackageService igPackageService = IgPackageService.create(vertx, pgPool);

            // Create validation services for different FHIR versions
            IGPackageIdentity igPackageIdentityR4 = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR4);
            IGPackageIdentity igPackageIdentityR4B = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR4B);
            IGPackageIdentity igPackageIdentityR5 = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR5);

            CompositeFuture.all(
                FhirValidationService.create(vertx, igPackageIdentityR4, igPackageService, profileServiceR4)
                    .onSuccess(service -> FhirValidationService.put(igPackageIdentityR4, service)),
                FhirValidationService.create(vertx, igPackageIdentityR4B, igPackageService, profileServiceR4B)
                    .onSuccess(service -> FhirValidationService.put(igPackageIdentityR4B, service)),
                FhirValidationService.create(vertx, igPackageIdentityR5, igPackageService, profileServiceR5)
                    .onSuccess(service -> FhirValidationService.put(igPackageIdentityR5, service))
            ).onComplete(ar -> {
                if (ar.succeeded()) {
                    promise.complete(new ValidationApi(vertx, pgPool));
                } else {
                    logger.error("Failed to initialize validation services", ar.cause());
                    promise.fail(ar.cause());
                }
            });
        });
    }

    // Private constructor
    private ValidationApi(Vertx vertx, Pool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    /**
     * Constructor for production use that initializes validation services internally.
     * 
     * @param vertx The Vert.x instance
     * @param pgPool The PostgresSQL connection pool
     */
    /*
    public ValidationApi(Vertx vertx, Pool pgPool) {

        FhirContextLoader fhirContextLoader = FhirContextLoader.getInstance();

        // Create FhirContext instances for different FHIR versions
        FhirContext fhirContextR4 = fhirContextLoader.getContext(SupportedFhirVersion.R4);
        FhirContext fhirContextR4B = fhirContextLoader.getContext(SupportedFhirVersion.R4B);
        FhirContext fhirContextR5 = fhirContextLoader.getContext(SupportedFhirVersion.R5);

        // Create profile services for different FHIR versions
        ProfileService profileServiceR4 =  ProfileService.create(vertx, fhirContextR4, pgPool);
        ProfileService profileServiceR4B = ProfileService.create(vertx, fhirContextR4B, pgPool);
        ProfileService profileServiceR5 = ProfileService.create(vertx, fhirContextR5, pgPool);

        // Create IG services for different FHIR versions
        IgPackageService igPackageService =  IgPackageService.create(vertx, pgPool);
        // Create validation services for different FHIR versions
        IGPackageIdentity igPackageIdentityR4 = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR4);
        IGPackageIdentity igPackageIdentityR4B = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR4B);
        IGPackageIdentity igPackageIdentityR5 = IGPackageIdentity.createIGPackageIdentityForCorePackage(fhirContextR5);

        // Initialize the services asynchronously
        CompositeFuture.all(
                FhirValidationService.create(vertx, igPackageIdentityR4, igPackageService, profileServiceR4)
                        .onSuccess(service -> FhirValidationService.put(igPackageIdentityR4, service)),
                FhirValidationService.create(vertx, igPackageIdentityR4B, igPackageService, profileServiceR4B)
                        .onSuccess(service -> FhirValidationService.put(igPackageIdentityR4B, service)),
                FhirValidationService.create(vertx, igPackageIdentityR5, igPackageService, profileServiceR5)
                        .onSuccess(service -> FhirValidationService.put(igPackageIdentityR5, service))
        ).onFailure(err -> {
            logger.error("Failed to initialize validation services", err);
            throw new RuntimeException("Failed to initialize validation services", err);
        });

    }
     */

    /**
     * Constructor for testing purposes that allows injection of validation services.
     * 
     * @param vertx The Vert.x instance
     * @param validationServices The pre-initialized validation services
     */
    public ValidationApi(Vertx vertx, HashMap<IGPackageIdentity, FhirValidationService> validationServices) {
        for (IGPackageIdentity igPackageIdentity : validationServices.keySet()) {
            FhirValidationService fhirValidationService = validationServices.get(igPackageIdentity);
            if (fhirValidationService == null) {
                logger.error("No validation service available for version: {}", igPackageIdentity.getFhirVersion());
                throw new RuntimeException("No validation service available for version: " + igPackageIdentity.getFhirVersion());
            }
            FhirValidationService.put(igPackageIdentity, fhirValidationService);
            logger.info("Validation service initialized for version: {}", igPackageIdentity.getFhirVersion());
        }
    }

    /**
     * Configures the routes for validation API endpoints.
     * 
     * @param router The Vert.x router to configure
     */
    public void includeRoutes(Router router) {
        router.post("/:version/validate")
                .handler(BodyHandler.create())
                .handler(this::handle);
    }


    private void handle(RoutingContext ctx) {
        // Validate content
        if (ctx.body().isEmpty()) {
            logger.error("Missing resource in request");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing resource").encode());
            return;
        }
        // Validate the version parameter
        if (!SupportedFhirVersion.isValid(ctx.pathParam("version").toUpperCase())) {
            logger.error("Invalid FHIR version: {}", ctx.pathParam("version"));
            ctx.response().setStatusCode(400).end(new JsonObject()
                    .put("error", "Invalid FHIR version: " + ctx.pathParam("version") + ". Supported versions are: " +
                            Arrays.toString(SupportedFhirVersion.values())).encode());
            return;
        }
        ValidationRequestContext validationRequestContext = ValidationRequestContext.fromRoutingContext(ctx);

        // Get the appropriate validation service based on the version
        FhirValidationService service = FhirValidationService.get(IGPackageIdentity.createIGPackageIdentityForCorePackage(FhirContextLoader.getInstance().getContext(validationRequestContext.getFhirVersion())));
        if (service == null) {
            logger.error("No validation service available for version: {}", validationRequestContext.getFhirVersion());
            ctx.response().setStatusCode(400).end(new JsonObject()
                .put("error", "Unsupported FHIR version: " + validationRequestContext.getFhirVersion().name()).encode());
            return;
        }

        service.validate(ctx.body().asString(), validationRequestContext)
                .onSuccess(result -> {
                    logger.info("Validation completed for resource using version: {}", validationRequestContext.getFhirVersion().name());
                    ctx.json(result);
                })
                .onFailure(err -> {
                    logger.error("Validation failed: {}", err.getMessage(), err);
                    ctx.response().setStatusCode(400).end(new JsonObject()
                        .put("error", err.getMessage()).encode());
                });
    }
}