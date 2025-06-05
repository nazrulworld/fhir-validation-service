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

    public HealthService(Vertx vertx, Pool pgPool) {
        this.vertx = vertx;
        this.pgPool = pgPool;
    }

    public Future<JsonObject> checkHealth() {
        JsonObject health = new JsonObject()
                .put("status", "UP")
                .put("timestamp", System.currentTimeMillis());

        return checkPostgres()
                .compose(pgStatus -> {
                    health.put("postgres", pgStatus);
                    return Future.succeededFuture(health);
                })
                .recover(err -> {
                    logger.error("Health check failed", err);
                    health.put("status", "DOWN")
                            .put("error", err.getMessage());
                    return Future.succeededFuture(health);
                });
    }

    private Future<JsonObject> checkPostgres() {
        return pgPool.query("SELECT 1").execute()
                .map(result -> new JsonObject()
                        .put("status", "UP")
                        .put("responseTime", System.currentTimeMillis()))
                .recover(err -> Future.succeededFuture(new JsonObject()
                        .put("status", "DOWN")
                        .put("error", err.getMessage())));
    }
}
