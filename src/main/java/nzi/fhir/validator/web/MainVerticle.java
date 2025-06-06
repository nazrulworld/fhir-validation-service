package nzi.fhir.validator.web;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import nzi.fhir.validator.web.config.PgConfig;
import nzi.fhir.validator.web.config.VerticleConfig;
import nzi.fhir.validator.web.endpoint.*;
import nzi.fhir.validator.web.service.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.config.ConfigRetriever;
import io.vertx.ext.web.handler.CorsHandler;

/**
 * @author Md Nazrul Islam
 */
public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LogManager.getLogger(MainVerticle.class);

    public static void main(String[] args) {
        Vertx.vertx().deployVerticle(new MainVerticle(), res -> {
            if (res.succeeded()) {
                System.out.println("MainVerticle deployed successfully");
            } else {
                System.err.println("Failed to deploy MainVerticle: " + res.cause().getMessage());
                res.cause().printStackTrace();
            }
        });
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigRetriever retriever = ConfigRetriever.create(vertx, VerticleConfig.getConfigRetrieverOptions());

        retriever.getConfig()
        .compose(config -> {
            Router router = Router.router(vertx);
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

            // Initialize Database Service first and chain the subsequent operations
            return DatabaseService.start(vertx)
                .onSuccess(service -> logger.info("Database service started successfully"))
                .compose(service -> {
                    // Initialize PostgresSQL
                    PgConnectOptions connectOptions = PgConfig.createPgOptions();
                    PoolOptions poolOptions = new PoolOptions()
                        .setMaxSize(15)
                        .setIdleTimeout(300000)
                        .setConnectionTimeout(5000);
                    
                    logger.info("Connecting to PostgresSQL at {}:{}***@{}:{}/{}", 
                        connectOptions.getUser(), 
                        connectOptions.getPassword().substring(0, connectOptions.getPassword().length() -3),
                        connectOptions.getHost(), 
                        connectOptions.getPort(), 
                        connectOptions.getDatabase());
                    
                    Pool pgPool = PgBuilder.pool()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(vertx)
                        .build();

                    // Create ValidationApi asynchronously
                    return ValidationApi.create(vertx, pgPool)
                        .compose(validationApi -> {
                            validationApi.includeRoutes(router);
                            
                            ProfileApi profileApi = new ProfileApi(vertx, pgPool);
                            profileApi.includeRoutes(router);

                            IgPackageService igPackageService = IgPackageService.create(vertx, pgPool);
                            IgPackageApi igPackageApi = new IgPackageApi(vertx, igPackageService);
                            igPackageApi.includeRoutes(router);

                            HealthService healthService = new HealthService(vertx, pgPool);
                            new HealthApi(router, vertx, healthService);

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
    }
}