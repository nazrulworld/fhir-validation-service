package nzi.fhir.validator.web.endpoint;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.model.IGPackageIdentity;
import nzi.fhir.validator.core.model.ValidatorIdentity;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.model.ValidationRequestContext;
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.service.ProfileService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;

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
                // R4
                FhirValidationService.create(vertx, SupportedFhirVersion.R4, igPackageService, profileServiceR4)
                    .compose(fhirValidationService ->
                            fhirValidationService.syncPreviousStateFromDatabase(pgPool).compose(v1 ->
                                fhirValidationService.saveSateToDatabase(pgPool).map(v2 -> fhirValidationService)
                            )
                        )
                    .onSuccess(service -> logger.info("Validation service initialized for version: {}", SupportedFhirVersion.R4.name())),

                // R4B
                FhirValidationService.create(vertx, SupportedFhirVersion.R4B, igPackageService, profileServiceR4B)
                    .compose(fhirValidationService ->
                            fhirValidationService.syncPreviousStateFromDatabase(pgPool).compose(v1 ->
                                    fhirValidationService.saveSateToDatabase(pgPool).map(v2 -> fhirValidationService)
                            )
                    )
                    .onSuccess(service -> logger.info("Validation service initialized for version: {}", SupportedFhirVersion.R4B.name())),
                FhirValidationService.create(vertx, SupportedFhirVersion.R5, igPackageService, profileServiceR5)
                    .compose(fhirValidationService ->
                            fhirValidationService.syncPreviousStateFromDatabase(pgPool).compose(v1 ->
                                    fhirValidationService.saveSateToDatabase(pgPool).map(v2 -> fhirValidationService)
                            )
                    )
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
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: POST, Path: "/:version/validate"
        routerBuilder.operation("validationApiValidate")
                .handler(this::handleDoValidation);
        // Method: POST, Path: "/:version/include-ig"
        routerBuilder.operation("validationApiIncludeIg")
                .handler(this::handleIncludeImplementationGuide);

    }


    private void handleDoValidation(RoutingContext ctx) {
        /*
         * @param ctx
         */
        try {
            if (ctx.body() == null || ctx.body().isEmpty()) {
                  logger.error("Missing resource in request");
                  ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(generateFatalError("Resource is missing in the request.").encode());
                  return;
            }

            String version = ctx.pathParam("version");
            // Validate the version parameter
            if (!SupportedFhirVersion.isValid(version.toUpperCase())) {
                logger.error("Invalid FHIR version: {}", version);
                ctx.response().setStatusCode(400).end(
                        generateFatalError("Invalid FHIR version: " + version + ". Supported versions are: " +
                                Arrays.toString(SupportedFhirVersion.values())).encode());
                return;
            }
            ValidationRequestContext.fromRoutingContext(ctx).onSuccess(validationRequestContext -> {
                // Get the appropriate validation service based on the version

                FhirValidationService service = FhirValidationService.get(validationRequestContext.getValidatorIdentity());
                if (service == null) {
                    logger.error("No validation service available for version: {}", validationRequestContext.getValidatorIdentity().getFhirVersion());
                    ctx.response()
                            .setStatusCode(400)
                            .end(generateFatalError("Unsupported FHIR version: " + validationRequestContext.getValidatorIdentity().getFhirVersion().name()).encode());
                    return;
                }
                logger.debug("ValidationRequest has been constructed from routing context. FHIR version was {}", validationRequestContext.getValidatorIdentity().getFhirVersion().name());

                service.validate(ctx.body().asString(), validationRequestContext)
                        .onSuccess(result -> {
                            logger.info("Validation completed for resource using version: {}", validationRequestContext.getValidatorIdentity().getFhirVersion().name());
                            ctx.response()
                                    .putHeader("Content-Type", validationRequestContext.getAcceptedContentType().getMimeType())
                                    .setStatusCode(200)
                                    .end(result.encode());
                        })
                        .onFailure(err -> {
                            logger.error("Validation failed: {}", err.getMessage(), err);
                            ctx.response()
                                    .setStatusCode(400)
                                    .end(generateFatalError(err).encode());
                        });
            }).onFailure(throwable -> {
                logger.error("Failed to construct ValidationRequest from routing context", throwable);
                ctx.response()
                    .setStatusCode(400)
                    .end(generateFatalError(throwable).encode());
            });
        } catch (BodyProcessorException e) {
            logger.error("Unexpected error processing the body content", e);
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(generateFatalError(e).encode());
        }
        catch (Exception e) {
            logger.error("Unexpected error processing request", e);
            ctx.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(generateFatalError("Internal server error while processing request").encode());
        }
    }

    private void handleIncludeImplementationGuide(RoutingContext ctx) {
        try {
            createIgPackageIdentityFromRequest(ctx)
                .compose(igPackageIdentity -> includeIgPackage(ctx, igPackageIdentity))
                .onComplete(result -> {
                    if (!ctx.response().ended()) {
                        if (result.succeeded()) {
                            ctx.response()
                                    .putHeader("Content-Type", "application/json")
                                    .setStatusCode(200)
                                    .end(new JsonObject().put("status", "success").encode());
                        } else {
                            logger.error("Failed to include implementation guide", result.cause());
                            ctx.response()
                                    .putHeader("Content-Type", "application/json")
                                    .setStatusCode(400)
                                    .end(generateFatalError(result.cause()).encode());
                        }
                    }
                });
        } catch (Exception e) {
            logger.error("Unexpected error processing request", e);
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end(generateFatalError("Internal server error while processing request").encode());
        }

    }

    private Future<Void> includeIgPackage(RoutingContext routingContext, IGPackageIdentity igPackageIdentity) {
        String requestId = routingContext.get("requestId", "unknown");
        logger.debug("Processing IG package request [{}] for package: {}", requestId, igPackageIdentity);

        if (igPackageIdentity == null) {
            return Future.failedFuture(new IllegalArgumentException("IGPackageIdentity cannot be null"));
        }

        return ValidationRequestContext.createValidatorIdentity(routingContext, pgPool)
            .compose(validatorIdentity -> {
                FhirValidationService validationService = FhirValidationService.get(validatorIdentity);
                if (validationService == null) {
                    String error = String.format("No validation service available for FHIR version: %s (Request ID: %s)",
                        validatorIdentity.getFhirVersion().name(), requestId);
                    logger.error(error);
                    return Future.failedFuture(error);
                }
                for (IGPackageIdentity ipi:  validationService.getIncludedIgPackagesListForNpmPackageValidation()){
                    if (ipi.getName().equals(igPackageIdentity.getName()) && ipi.getVersion().equals(igPackageIdentity.getVersion())){
                        logger.info("IG package already included in validation service for package: {}", igPackageIdentity);
                        return Future.succeededFuture();
                    }
                }
                return validationService.addNpmIgPackage(igPackageIdentity);
            })
            .onSuccess(v -> {
                synchronized(routingContext.response()) {
                    if (!routingContext.response().ended()) {
                        routingContext.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(200)
                            .end(new JsonObject().put("status", "success").encode());
                    }
                }
            })
            .onFailure(err -> {
                synchronized(routingContext.response()) {
                    if (!routingContext.response().ended()) {
                        logger.error("Failed to process IG package request [{}]: {}", requestId, err.getMessage(), err);
                        int statusCode = determineStatusCode(err);
                        routingContext.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(statusCode)
                            .end(generateFatalError(err).encode());
                    }
                }
            });
    }

    private int determineStatusCode(Throwable err) {
        if (err instanceof IllegalArgumentException) {
            return 400; // Bad Request
        } else if (err instanceof SecurityException) {
            return 403; // Forbidden
        } else {
            return 500; // Internal Server Error
        }
    }
    private JsonObject generateFatalError(Throwable throwable){
        return generateFatalError(throwable.getMessage());
    }

    private JsonObject generateFatalError(String message){
        JsonObject error = new JsonObject();
        error.put("severity", "FATAL");
        error.put("location", "n/a");
        error.put("message", message);

        return new JsonObject().put("valid", false).put("messages", new JsonArray(List.of(error)));
    }

    private Future<IGPackageIdentity> createIgPackageIdentityFromRequest(RoutingContext routingContext) {
        JsonObject requestBody = routingContext.body().asJsonObject();
        if (requestBody == null) {
            return Future.failedFuture("Request body is null");
        }
        String packageId = requestBody.getString("igPackageId");
        if (packageId == null) {
            return Future.failedFuture("`packageId` value is required. But doesn't found in request  " + requestBody.encode());
        }
        String igVersion = requestBody.getString("igPackageVersion", "latest");
        SupportedFhirVersion fhirVersion;
        try {
             fhirVersion = SupportedFhirVersion.valueOf(routingContext.pathParam("version").toUpperCase());
        } catch (IllegalArgumentException e) {
            return Future.failedFuture("Invalid FHIR version: " + routingContext.pathParam("version"));
        }

        return IgPackageService.isIgPackageExist(pgPool, packageId, igVersion).compose(exists -> {
            if (!exists) {
                return Future.failedFuture("IG package not found: " + packageId + ":" + igVersion);
            }
            if("latest".equalsIgnoreCase(igVersion)) {
                return IgPackageService.resolveLatestIgPackageVersion(pgPool, packageId).compose(
                        resolvedVersion -> {
                        if(resolvedVersion == null) {
                            return Future.failedFuture("IG package not found: " + packageId + ":" + igVersion);
                        }
                        return Future.succeededFuture(new IGPackageIdentity(packageId, resolvedVersion, fhirVersion));
                        }
                );
            }
            return Future.succeededFuture(new IGPackageIdentity(packageId, igVersion, fhirVersion));
        });
    }
}