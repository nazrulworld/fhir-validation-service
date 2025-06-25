package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.service.HealthService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author Md Nazrul Islam
 */
public class HealthApi {
    private static final Logger logger = LogManager.getLogger(HealthApi.class);
    private final HealthService healthService;

    public HealthApi(RouterBuilder routerBuilder, Vertx vertx, HealthService healthService) {
        this.healthService = healthService;
        // Method: GET, Path: "/health"
        routerBuilder.operation("healthApiHealth")
                .handler(ctx -> {
                    logger.debug("Received health check request");
                    healthService.checkHealth()
                            .onSuccess(health -> {
                                int statusCode = "UP".equals(health.getString("status")) ? 200 : 503;
                                ctx.response()
                                        .setStatusCode(statusCode)
                                        .putHeader("Content-Type", "application/json")
                                        .end(health.encode());
                                logger.debug("Health report was {}", health.encode());
                            })
                            .onFailure(err -> {
                                logger.error("Health check failed", err);
                                ctx.response()
                                        .setStatusCode(503)
                                        .putHeader("Content-Type", "application/json")
                                        .end(new JsonObject()
                                                .put("status", "DOWN")
                                                .put("error", err.getMessage())
                                                .encode());
                            });
                });

        // Kubernetes liveness probe endpoint
        // Method: GET, Path: "/health/liveness"
        routerBuilder.operation("healthApiLiveness")
                .handler(ctx -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                                .put("status", "UP")
                                .put("timestamp", System.currentTimeMillis())
                                .encode()));
    }
}
