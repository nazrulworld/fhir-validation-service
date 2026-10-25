package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Health, liveness and readiness. Readiness is computed from state kept current in the background (database ping
 * every 2 s, validator state, draining flag) so probes never wait on I/O.
 *
 * @author Md Nazrul Islam
 */
public class HealthService {
    private static final Logger logger = LogManager.getLogger(HealthService.class);
    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String POSTGRES_HEALTH_QUERY = "SELECT 1";
    private static final long PING_INTERVAL_MS = 2_000;
    private static final long DEFAULT_DB_STALENESS_MS = 10_000;

    private final Vertx vertx;
    private final Pool pgPool;
    private final long dbStalenessMs;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile long lastDbSuccessAt = 0;
    private volatile String lastDbError = "not checked yet";
    private volatile Supplier<List<String>> validatorReadiness = () -> List.of("validators not initialised");
    private volatile Supplier<JsonObject> validatorDetails = JsonObject::new;
    private long timerId = -1;

    public HealthService(Vertx vertx, Pool pgPool) {
        this(vertx, pgPool, DEFAULT_DB_STALENESS_MS);
    }

    public HealthService(Vertx vertx, Pool pgPool, long dbStalenessMs) {
        this.vertx = vertx;
        this.pgPool = pgPool;
        this.dbStalenessMs = dbStalenessMs;
    }

    /**
     * Starts the background database ping used by readiness.
     */
    public HealthService start() {
        ping();
        timerId = vertx.setPeriodic(PING_INTERVAL_MS, id -> ping());
        return this;
    }

    public void stop() {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    /**
     * @param notReadyReasons returns an empty list when every enabled FHIR version can be served
     * @param details         per-version detail for the {@code /health} report
     */
    public void setValidatorReadiness(Supplier<List<String>> notReadyReasons, Supplier<JsonObject> details) {
        this.validatorReadiness = notReadyReasons;
        this.validatorDetails = details;
    }

    public void setDraining(boolean value) {
        draining.set(value);
    }

    public boolean isDraining() {
        return draining.get();
    }

    private void ping() {
        pgPool.query(POSTGRES_HEALTH_QUERY).execute()
                .onSuccess(r -> {
                    lastDbSuccessAt = System.currentTimeMillis();
                    lastDbError = null;
                })
                .onFailure(err -> {
                    if (lastDbError == null) {
                        logger.warn("Database ping failed: {}", err.getMessage());
                    }
                    lastDbError = err.getMessage();
                });
    }

    /**
     * @return {@code {status: UP|DOWN, reasons: [...], ...}}; never performs I/O
     */
    public JsonObject readiness() {
        List<String> reasons = new ArrayList<>();
        if (draining.get()) {
            reasons.add("instance is draining");
        }
        long age = System.currentTimeMillis() - lastDbSuccessAt;
        if (age > dbStalenessMs) {
            reasons.add("database not reachable" + (lastDbError != null ? ": " + lastDbError : ""));
        }
        reasons.addAll(validatorReadiness.get());
        JsonObject result = new JsonObject()
                .put("status", reasons.isEmpty() ? STATUS_UP : STATUS_DOWN)
                .put("timestamp", System.currentTimeMillis())
                .put("versions", validatorDetails.get());
        if (!reasons.isEmpty()) {
            result.put("reasons", new JsonArray(reasons));
        }
        return result;
    }

    public Future<JsonObject> checkHealth() {
        long timestamp = System.currentTimeMillis();
        return checkPostgresConnection(timestamp)
                .map(pgStatus -> new JsonObject()
                        .put("status", STATUS_UP.equals(pgStatus.getString("status")) ? STATUS_UP : STATUS_DOWN)
                        .put("timestamp", timestamp)
                        .put("postgres", pgStatus)
                        .put("readiness", readiness()));
    }

    private Future<JsonObject> checkPostgresConnection(long timestamp) {
        return pgPool.query(POSTGRES_HEALTH_QUERY).execute()
                .map(result -> new JsonObject()
                        .put("status", STATUS_UP)
                        .put("responseTime", System.currentTimeMillis() - timestamp))
                .recover(err -> Future.succeededFuture(new JsonObject()
                        .put("status", STATUS_DOWN)
                        .put("error", "database not reachable")));
    }
}
