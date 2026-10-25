package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.web.ErrorResponder;

/**
 * Health report, liveness and readiness probes.
 *
 * @author Md Nazrul Islam
 */
public class HealthApi {

    public HealthApi(RouterBuilder routerBuilder, Vertx vertx, HealthService healthService) {
        // Method: GET, Path: "/health"
        routerBuilder.operation("healthApiHealth")
                .handler(ctx -> ErrorResponder.respond(ctx, healthService.checkHealth(), (rc, health) -> rc.response()
                        .setStatusCode("UP".equals(health.getString("status")) ? 200 : 503)
                        .putHeader("Content-Type", "application/json")
                        .end(health.encode())));

        // Method: GET, Path: "/health/liveness" - process is alive; never depends on dependencies.
        routerBuilder.operation("healthApiLiveness")
                .handler(ctx -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                                .put("status", "UP")
                                .put("timestamp", System.currentTimeMillis())
                                .encode()));

        // Method: GET, Path: "/health/readiness" - may this instance receive traffic?
        routerBuilder.operation("healthApiReadiness")
                .handler(ctx -> {
                    JsonObject readiness = healthService.readiness();
                    ctx.response()
                            .setStatusCode("UP".equals(readiness.getString("status")) ? 200 : 503)
                            .putHeader("Content-Type", "application/json")
                            .end(readiness.encode());
                });
    }
}
