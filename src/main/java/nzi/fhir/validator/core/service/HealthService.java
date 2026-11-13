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
    private volatile java.util.function.LongSupplier catalogRevision = () -> 0L;
    private volatile Supplier<JsonObject> terminologyStatus = () -> null;
    /** Spec 009 FR-015: the FHIR versions this instance serves, reported under {@code fhirVersions.served}. */
    private volatile nzi.fhir.validator.core.enums.ServedVersions servedVersions = nzi.fhir.validator.core.enums.ServedVersions.ALL;
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

    /** The package catalog revision this instance has loaded, shown in the report (spec 004 FR-032). */
    public void setCatalogRevision(java.util.function.LongSupplier catalogRevision) {
        this.catalogRevision = catalogRevision;
    }

    /** The terminology allowlist state shown in the {@code /health} report (spec 007, contracts §5). */
    public void setTerminologyStatus(Supplier<JsonObject> terminologyStatus) {
        this.terminologyStatus = terminologyStatus == null ? () -> null : terminologyStatus;
    }

    public void setServedVersions(nzi.fhir.validator.core.enums.ServedVersions served) {
        this.servedVersions = served;
    }

    /** Spec 009 FR-023, FR-030: reported under {@code security}. */
    private volatile JsonObject security = new JsonObject().put("aclSecurity", "enabled").put("adminNetworkRestricted", false);

    public void setSecurity(nzi.fhir.validator.core.security.AclMode mode, boolean adminNetworkRestricted) {
        this.security = new JsonObject().put("aclSecurity", mode.value()).put("adminNetworkRestricted", adminNetworkRestricted);
    }

    /** Spec 009 (research R15): inactive contexts, as last counted; read by the {@code fv_validator_contexts_inactive} gauge. */
    private final java.util.concurrent.atomic.AtomicLong inactiveCount = new java.util.concurrent.atomic.AtomicLong();

    public long inactiveCount() {
        return inactiveCount.get();
    }

    /** The number of inactive contexts, as counted by the configuration sync (start and every poll). */
    public void setInactiveCount(long count) {
        inactiveCount.set(count);
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
            // The driver's error text names hosts and internals: it is logged by ping(), never returned (Principle IV).
            reasons.add("database not reachable");
        }
        reasons.addAll(validatorReadiness.get());
        JsonObject result = new JsonObject()
                .put("status", reasons.isEmpty() ? STATUS_UP : STATUS_DOWN)
                .put("timestamp", System.currentTimeMillis())
                .put("catalogRevision", catalogRevision.getAsLong())
                .put("versions", validatorDetails.get());
        if (!reasons.isEmpty()) {
            result.put("reasons", new JsonArray(reasons));
        }
        return result;
    }

    /** Spec 009 FR-015: at most this many inactive contexts are listed (contracts/http-api.md §4). */
    static final int MAX_INACTIVE_LISTED = 100;
    private static final String INACTIVE_CONTEXTS_QUERY = "SELECT s.fhir_version, s.ig_package, o.name_short, s.inactive_reason, "
            + "count(*) OVER () FROM " + nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME
            + ".fhir_validator_snapshots s JOIN " + nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME
            + ".organisations o ON o.id = s.owner WHERE NOT s.is_active ORDER BY 1, 2 LIMIT " + MAX_INACTIVE_LISTED;

    /**
     * Stored validator contexts that are inactive (spec 009 FR-015): {@code {count, items}}, or {@code null} when the
     * query fails (the field is then left out; health never fails because of it).
     */
    public Future<JsonObject> inactiveContexts() {
        return pgPool.query(INACTIVE_CONTEXTS_QUERY).execute()
                .map(rows -> {
                    JsonArray items = new JsonArray();
                    long count = 0;
                    for (io.vertx.sqlclient.Row r : rows) {
                        count = r.getLong(4);
                        items.add(new JsonObject().put("fhirVersion", r.getString(0)).put("igPackage", r.getString(1))
                                .put("owner", r.getString(2)).put("reason", r.getString(3)));
                    }
                    inactiveCount.set(count);
                    return new JsonObject().put("count", count).put("items", items);
                })
                .otherwise(err -> {
                    logger.debug("Inactive validator contexts could not be read: {}", err.getMessage());
                    return null;
                });
    }

    public Future<JsonObject> checkHealth() {
        long timestamp = System.currentTimeMillis();
        Future<JsonObject> inactive = inactiveContexts();
        return checkPostgresConnection(timestamp)
                .compose(pgStatus -> inactive.map(i -> new JsonObject[]{pgStatus, i}))
                .map(results -> {
                    JsonObject pgStatus = results[0];
                    JsonObject inactiveContexts = results[1];
                    JsonObject health = new JsonObject()
                            .put("status", STATUS_UP.equals(pgStatus.getString("status")) ? STATUS_UP : STATUS_DOWN)
                            .put("timestamp", timestamp)
                            .put("postgres", pgStatus)
                            .put("readiness", readiness());
                    // Spec 009 FR-015 (contracts/http-api.md §4).
                    JsonArray served = new JsonArray();
                    servedVersions.asList().forEach(v -> served.add(v.name()));
                    JsonObject fhirVersions = new JsonObject().put("served", served);
                    if (inactiveContexts != null) {
                        fhirVersions.put("inactiveContexts", inactiveContexts);
                    }
                    health.put("fhirVersions", fhirVersions);
                    health.put("security", security.copy());
                    // Spec 007: reported, but readiness never depends on it (terminology is optional).
                    JsonObject terminology = terminologyStatus.get();
                    if (terminology != null) {
                        health.put("terminology", terminology);
                    }
                    return health;
                });
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
