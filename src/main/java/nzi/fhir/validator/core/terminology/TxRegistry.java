package nzi.fhir.validator.core.terminology;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The terminology allowlist's source: the official HL7 terminology registry (spec 007 FR-006, FR-007, FR-012, research
 * R10). Its address is fixed ({@link #OFFICIAL_BASE}); nothing in production can change it. At start the last good
 * list per FHIR version is read from {@code tx_registry_snapshot}, then every enabled version is fetched, one after
 * another, and again every {@code FV_TX_REGISTRY_REFRESH_MS}. A failed fetch keeps the previous list; a version for
 * which no list was ever obtained uses the built-in fallback list.
 * <p>
 * Each instance fetches on its own (no coordination): two instances may hold different lists for at most one refresh
 * interval, which is safe because both come from the same registry and are enforced locally on every call (Principle
 * II). Everything here is non-blocking (Principle I).
 */
public final class TxRegistry implements TxServerAllowlistSource {
    private static final Logger logger = LogManager.getLogger(TxRegistry.class);

    /** The official HL7 terminology registry (FR-006). */
    public static final String OFFICIAL_BASE = "https://ecosystem.hl7.org/tx-reg";

    private static final String TABLE = ApplicationConfig.DB_POSTGRES_SCHEMA_NAME + ".tx_registry_snapshot";

    private final Vertx vertx;
    private final WebClient client;
    private final Pool pool;
    private final String base;
    private final NetworkGuard guard;
    private final long refreshMs;
    private final long timeoutMs;
    /** Set only by the test JVM ({@code fv.tx.registry.offline}): never fetch, keep the stored or built-in list. */
    private final boolean offline;
    private volatile TxServerAllowlist allowlist;
    private long timer = -1;

    /**
     * Tests only (research R14): a stub registry at {@code base}, a guard that allows loopback, and test defaults.
     *
     * @param initial the starting allowlist (normally {@link TxServerAllowlist#builtIn()})
     */
    TxRegistry(Vertx vertx, Pool pool, String base, NetworkGuard guard, long refreshMs, long timeoutMs,
               TxServerAllowlist initial) {
        this(vertx, pool, base, guard, refreshMs, timeoutMs, initial, false);
    }

    private TxRegistry(Vertx vertx, Pool pool, String base, NetworkGuard guard, long refreshMs, long timeoutMs,
                       TxServerAllowlist initial, boolean offline) {
        this.offline = offline;
        this.vertx = vertx;
        this.pool = pool;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.guard = guard;
        this.refreshMs = refreshMs;
        this.timeoutMs = timeoutMs;
        this.allowlist = initial;
        this.client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMs))
                .setUserAgent("fhir-validation-service"));
    }

    /** The production registry: {@link #OFFICIAL_BASE}, public addresses only. */
    public static TxRegistry official(Vertx vertx, Pool pool, long refreshMs, long timeoutMs) {
        // The offline switch can only make the service contact less (nothing at all); it never changes the address.
        return new TxRegistry(vertx, pool, OFFICIAL_BASE, NetworkGuard.strict(), refreshMs, timeoutMs,
                TxServerAllowlist.builtIn(), Boolean.getBoolean("fv.tx.registry.offline"));
    }

    public String base() {
        return base;
    }

    public NetworkGuard guard() {
        return guard;
    }

    @Override
    public TxServerAllowlist current() {
        return allowlist;
    }

    /** Tests: points the default server (tx.fhir.org) of {@code version} at {@code url}. */
    void setDefaultServer(SupportedFhirVersion version, String url) {
        allowlist = allowlist.withDefault(version, url);
    }

    /**
     * Loads the stored lists, then starts fetching in the background (the returned future does not wait for the
     * registry, so start-up and readiness never depend on it).
     */
    public Future<Void> start() {
        return loadStored().recover(err -> {
            logger.warn("Could not read the stored terminology server lists: {}", err.getMessage());
            return Future.succeededFuture();
        }).onComplete(ar -> {
            if (offline) {
                logger.info("Terminology registry fetching is off (test JVM); using the stored or built-in list");
                return;
            }
            refreshNow();
            timer = vertx.setPeriodic(refreshMs, id -> refreshNow());
        });
    }

    public void stop() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
            timer = -1;
        }
        client.close();
    }

    /** Fetches every enabled version once, one after another; completes when all have been tried. */
    public Future<Void> refreshNow() {
        Future<Void> chain = Future.succeededFuture();
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            chain = chain.compose(x -> fetch(v).recover(err -> {
                logger.warn("Terminology registry list for {} not refreshed ({}); keeping the previous list", v,
                        err.getMessage());
                return Future.succeededFuture();
            }));
        }
        return chain;
    }

    /** The registry's name for a FHIR version in its {@code fhirVersion} query parameter. */
    static String registryCode(SupportedFhirVersion version) {
        return switch (version) {
            case STU3 -> "R3";
            case R4 -> "R4";
            case R4B -> "R4B";
            case R5 -> "R5";
            case R6 -> "R6";
        };
    }

    private Future<Void> fetch(SupportedFhirVersion version) {
        return client.getAbs(base + "/")
                .addQueryParam("fhirVersion", registryCode(version))
                .putHeader("Accept", "application/json")
                .timeout(timeoutMs)
                .send()
                .compose(r -> {
                    if (r.statusCode() != 200) {
                        return Future.failedFuture("HTTP " + r.statusCode());
                    }
                    JsonObject body;
                    try {
                        body = r.bodyAsJsonObject();
                    } catch (RuntimeException e) {
                        return Future.failedFuture("the answer is not JSON");
                    }
                    if (body == null || !(body.getValue("results") instanceof JsonArray results)) {
                        return Future.failedFuture("the answer has no results");
                    }
                    List<TxServerEntry> kept = new ArrayList<>();
                    results.forEach(o -> {
                        if (o instanceof JsonObject e) {
                            TxServerEntry.fromRegistry(e).filter(x -> x.fhirVersion() == version).ifPresent(kept::add);
                        }
                    });
                    Instant fetchedAt = Instant.now();
                    Instant lastUpdate = parseInstant(body.getValue("last-update")).orElse(null);
                    allowlist = allowlist.withVersion(version, TxServerAllowlist.VersionList.of(kept,
                            TxServerAllowlist.Source.REGISTRY, fetchedAt, lastUpdate));
                    logger.info("Terminology registry lists {} open server(s) for {}", kept.size(), version);
                    return store(version, kept, fetchedAt, lastUpdate);
                });
    }

    private Future<Void> store(SupportedFhirVersion version, List<TxServerEntry> kept, Instant fetchedAt, Instant lastUpdate) {
        if (pool == null) {
            return Future.succeededFuture();
        }
        JsonArray body = new JsonArray();
        kept.forEach(e -> body.add(e.toJson()));
        return pool.preparedQuery("INSERT INTO " + TABLE + " (fhir_version, fetched_at, registry_last_update, body) "
                        + "VALUES ($1, $2, $3, $4) ON CONFLICT (fhir_version) DO UPDATE SET fetched_at = EXCLUDED.fetched_at, "
                        + "registry_last_update = EXCLUDED.registry_last_update, body = EXCLUDED.body "
                        + "WHERE " + TABLE + ".fetched_at < EXCLUDED.fetched_at")
                .execute(Tuple.of(version.name(), fetchedAt.atOffset(ZoneOffset.UTC),
                        lastUpdate == null ? null : lastUpdate.atOffset(ZoneOffset.UTC), body))
                .<Void>mapEmpty()
                .recover(err -> {
                    logger.warn("Could not store the terminology server list for {}: {}", version, err.getMessage());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> loadStored() {
        if (pool == null) {
            return Future.succeededFuture();
        }
        return pool.query("SELECT fhir_version, fetched_at, registry_last_update, body::text FROM " + TABLE).execute()
                .map(rows -> {
                    Map<SupportedFhirVersion, TxServerAllowlist.VersionList> lists = new EnumMap<>(SupportedFhirVersion.class);
                    for (Row row : rows) {
                        SupportedFhirVersion.parseEnabled(row.getString(0)).ifPresent(v -> {
                            List<TxServerEntry> entries = new ArrayList<>();
                            new JsonArray(row.getString(3)).forEach(o -> {
                                if (o instanceof JsonObject j) {
                                    TxServerEntry.fromStored(j).filter(e -> e.fhirVersion() == v).ifPresent(entries::add);
                                }
                            });
                            OffsetDateTime fetched = row.getOffsetDateTime(1);
                            OffsetDateTime last = row.getOffsetDateTime(2);
                            lists.put(v, TxServerAllowlist.VersionList.of(entries, TxServerAllowlist.Source.STORED_REGISTRY,
                                    fetched == null ? null : fetched.toInstant(), last == null ? null : last.toInstant()));
                        });
                    }
                    TxServerAllowlist next = allowlist;
                    for (Map.Entry<SupportedFhirVersion, TxServerAllowlist.VersionList> e : lists.entrySet()) {
                        // Never replace a list this instance already fetched itself.
                        if (!next.list(e.getKey()).map(l -> l.source() == TxServerAllowlist.Source.REGISTRY).orElse(false)) {
                            next = next.withVersion(e.getKey(), e.getValue());
                        }
                    }
                    allowlist = next;
                    return null;
                });
    }

    private static Optional<Instant> parseInstant(Object value) {
        if (!(value instanceof String s)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(s));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The {@code terminology} object of the {@code /health} report (contracts §5). */
    public JsonObject status() {
        return new JsonObject().put("registry", OFFICIAL_BASE).put("allowlist", allowlist.status());
    }
}
