package nzi.fhir.validator.web;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.PoolOptions;
import nzi.fhir.validator.web.config.PgConfig;
import nzi.fhir.validator.web.config.RedisConfig;
import nzi.fhir.validator.web.endpoint.*;
import nzi.fhir.validator.web.service.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.config.ConfigRetriever;
import io.vertx.ext.web.handler.CorsHandler;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LogManager.getLogger(MainVerticle.class);

    public static void main(String[] args) {
        Vertx.vertx().deployVerticle(new MainVerticle());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigRetriever.create(vertx)
        .getConfig()
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

            // Initialize Redis
            RedisAPI redis = RedisConfig.createRedisClient(vertx);

            // Initialize PostgreSQL
            PgConnectOptions connectOptions = PgConfig.createPgOptions();
            PoolOptions poolOptions = new PoolOptions().setMaxSize(5);
            Pool pgPool = PgBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

            // Initialize WebClient
            WebClient webClient = WebClient.create(vertx);

            // Initialize services
            String fhirVersion = config.getString("fhirVersion", "R4");
            IgService igService = new IgService(vertx, pgPool, redis);
            ProfileService profileService = new ProfileService(vertx, fhirVersion, redis, pgPool);
            FhirValidationService validationService = new FhirValidationService(vertx, fhirVersion, profileService, igService);

            // Register API endpoints
            new ValidationApi(router, vertx, validationService);
            new ProfileApi(router, vertx, profileService);
            new IgApi(router, vertx, igService, webClient);

            // Start HTTP server
            int port = config.getInteger("http.port", 8080);
            return vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> logger.info("HTTP server started on port {}", port))
                .mapEmpty();
        })
        .onComplete(ar -> {
            if (ar.succeeded()) {
                startPromise.complete();
            } else {
                startPromise.fail(ar.cause());
            }
        });
}

}
