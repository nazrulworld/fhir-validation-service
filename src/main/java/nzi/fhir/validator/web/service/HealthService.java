package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * @author Md Nazrul Islam
 */
public class HealthService {
    private static final Logger logger = LogManager.getLogger(HealthService.class);
    private final Vertx vertx;
    private final Pool pgPool;
    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String POSTGRES_HEALTH_QUERY = "SELECT 1";

    public HealthService(Vertx vertx, Pool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    public Future<JsonObject> checkHealth() {
        long timestamp = System.currentTimeMillis();
        return checkPostgresConnection(timestamp)
                .map(pgStatus -> new JsonObject()
                        .put("status", STATUS_UP)
                        .put("timestamp", timestamp)
                        .put("postgres", pgStatus))
                .recover(err -> {
                    logger.error("Health check failed", err);
                    return Future.succeededFuture(new JsonObject()
                            .put("status", STATUS_DOWN)
                            .put("timestamp", timestamp)
                            .put("error", err.getMessage()));
                });
    }

    private Future<JsonObject> checkPostgresConnection(long timestamp) {
        return pgPool.query(POSTGRES_HEALTH_QUERY).execute()
                .map(result -> createSuccessResponse(timestamp))
                .recover(err -> Future.succeededFuture(createErrorResponse(err)));
    }

    private JsonObject createSuccessResponse(long timestamp) {
        return new JsonObject()
                .put("status", STATUS_UP)
                .put("responseTime", timestamp);
    }

    private JsonObject createErrorResponse(Throwable error) {
        return new JsonObject()
                .put("status", STATUS_DOWN)
                .put("error", error.getMessage());
    }
}