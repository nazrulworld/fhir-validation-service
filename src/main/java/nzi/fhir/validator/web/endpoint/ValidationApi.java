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
import nzi.fhir.validator.model.ValidatorIdentity;
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

    // Private constructor
    private ValidationApi(Vertx vertx, Pool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    public static ValidationApi createInstance(Vertx vertx, Pool pgPool) {
        return new ValidationApi(vertx, pgPool);
    }

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

            CompositeFuture.all(
                FhirValidationService.create(vertx, SupportedFhirVersion.R4, igPackageService, profileServiceR4)
                    .onSuccess(service -> logger.info("Validation service initialized for version: {}", SupportedFhirVersion.R4.name())),
                FhirValidationService.create(vertx, SupportedFhirVersion.R4B, igPackageService, profileServiceR4B)
                    .onSuccess(service -> logger.info("Validation service initialized for version: {}", SupportedFhirVersion.R4B.name())),
                FhirValidationService.create(vertx, SupportedFhirVersion.R5, igPackageService, profileServiceR5)
                    .onSuccess(service -> logger.info("Validation service initialized for version: {}", SupportedFhirVersion.R5.name()))
            ).onComplete(ar -> {
                if (ar.succeeded()) {
                    promise.complete(createInstance(vertx, pgPool));
                } else {
                    logger.error("Failed to initialize validation services", ar.cause());
                    promise.fail(ar.cause());
                }
            });
        });
    }
    /**
     * Configures the routes for validation API endpoints.
     * 
     * @param router The Vert.x router to configure
     */
    public void includeRoutes(Router router) {
        router.post("/:version/validate")
                .handler(BodyHandler.create())
                .handler(this::handleDoValidate);
    }


    private void handleDoValidate(RoutingContext ctx) {
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
        logger.debug("ValidationRequest has been constructed from routing context. FHIR version was {}", validationRequestContext.getFhirVersion().name());

        FhirContext fhirContext = FhirContextLoader.getInstance().getContext(validationRequestContext.getFhirVersion());
        logger.debug("Validator is initiating using FHIR Context {}, for FHIR version {}", fhirContext.toString(), fhirContext.getVersion().getVersion());

        // create validator identity
        ValidatorIdentity validatorIdentity = ValidatorIdentity.createFromFhirVersion(validationRequestContext.getFhirVersion());
        // Get the appropriate validation service based on the version
        FhirValidationService service = FhirValidationService.get(validatorIdentity);
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