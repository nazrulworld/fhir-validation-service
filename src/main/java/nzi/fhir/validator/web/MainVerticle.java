package nzi.fhir.validator.web;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.openapi.RouterBuilderOptions;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.config.PgConfig;
import nzi.fhir.validator.core.config.VerticleConfig;
import nzi.fhir.validator.web.endpoint.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.config.ConfigRetriever;
import io.vertx.ext.web.handler.CorsHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * @author Md Nazrul Islam
 */
public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LogManager.getLogger(MainVerticle.class);
    private static final long MAX_UPLOAD_SIZE = 20_000_000L; // 20MB
    private static final String UPLOAD_DIR_NAME = "ig-uploads";

    public static void main(String[] args) {
        Vertx.vertx().deployVerticle(new MainVerticle(), res -> {
            if (res.succeeded()) {
                logger.info("MainVerticle deployed successfully.");
            } else {
                logger.error("Failed to deploy MainVerticle: {}", res.cause().getMessage());
            }
        });
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigRetriever retriever = ConfigRetriever.create(vertx, VerticleConfig.getConfigRetrieverOptions());

        RouterBuilder.create(vertx, "openapi.yaml")
        .onSuccess(routerBuilder -> {
            // Configure global body handler options for file uploads
            RouterBuilderOptions options = new RouterBuilderOptions()
                    .setMountResponseContentTypeHandler(true)
                    .setRequireSecurityHandlers(false);

            // Apply the options to the router builder
            routerBuilder.setOptions(options);
            // Add the body handler separately
            routerBuilder.rootHandler(BodyHandler.create()
                    .setUploadsDirectory(getUploadDirectory())
                    .setBodyLimit(MAX_UPLOAD_SIZE)
                    .setDeleteUploadedFilesOnEnd(true));

            /*
            // Add global error handler for parameter validation
            routerBuilder.rootHandler(context -> {
                context.put("requestId", UUID.randomUUID().toString());
                context.next();
            });
            routerBuilder.rootHandler(context -> {
                if (context.failure() instanceof BodyProcessorException || context.failure() instanceof ParameterProcessorException) {
                    context.response().putHeader("Content-Type", "application/json");
                    context.response()
                            .setStatusCode(400)
                            .end(new JsonObject()
                                    .put("status", "error")
                                    .put("error", "Invalid request body format")
                                    .encode());
                } else {
                    context.next();
                }
            });
             */
            retriever.getConfig()
            .compose(config -> {
                // Initialize Database Service first and chain the subsequent operations
                return DatabaseService.start(vertx)
                        .onSuccess(service -> logger.info("Database service started successfully"))
                        .compose(service -> {
                            // Initialize PostgresSQL
                            Pool pgPool = PgConfig.createPgPool(vertx);

                            // Create ValidationApi asynchronously
                            return ValidationApi.create(vertx, pgPool)
                                    .compose(validationApi -> {
                                    validationApi.includeRoutes(routerBuilder);

                                    ProfileApi profileApi = new ProfileApi(vertx, pgPool);
                                    profileApi.includeRoutes(routerBuilder);

                                    IgPackageService igPackageService = IgPackageService.create(vertx, pgPool);
                                    IgPackageApi igPackageApi = new IgPackageApi(vertx, igPackageService);
                                    igPackageApi.includeRoutes(routerBuilder);

                                    HealthService healthService = new HealthService(vertx, pgPool);
                                    new HealthApi(routerBuilder, vertx, healthService);

                                    Router router = routerBuilder.createRouter();
                                    // Setup Cros
                                    setCros(router);

                                    // Start HTTP server
                                    int port = config.getJsonObject("http", new JsonObject()).getInteger("port", 8080);
                                    return vertx.createHttpServer()
                                            .requestHandler(router)
                                            .listen(port)
                                            .onSuccess(server -> logger.info("HTTP server started on port {}", port))
                                            .mapEmpty();
                                });
                        });
            })
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    startPromise.complete();
                } else {
                    logger.error("Failed to start MainVerticle", ar.cause());
                    startPromise.fail(ar.cause());
                }
            });
        })
        .onFailure(err -> {
            logger.error("Failed to load OpenAPI specification", err);
            startPromise.fail(err);
        });
    }


    private void setCros(Router router) {
        // CORS setup
        router.route().handler(CorsHandler.create()
                .addRelativeOrigin(".*")
                .allowedMethod(io.vertx.core.http.HttpMethod.GET)
                .allowedMethod(io.vertx.core.http.HttpMethod.POST)
                .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
                .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
                .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
                .allowedHeader("Content-Type")
                .allowedHeader("Authorization")
                .allowedHeader("X-Requested-With")
                .allowedHeader("Accept"));
    }
    // Initialize the upload directory more safely
    private String getUploadDirectory() {
        Path uploadDir = Paths.get(System.getProperty("java.io.tmpdir"), UPLOAD_DIR_NAME);
        try {
            Files.createDirectories(uploadDir);
            return uploadDir.toString();
        } catch (IOException e) {
            logger.error("Failed to create upload directory", e);
            throw new RuntimeException("Could not initialize upload directory", e);
        }
    }
}