package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.pubsub.PgSubscriber;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCache;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps this instance's validator contexts in line with the shared configuration and package catalog (research R3,
 * R4): a {@code LISTEN} on {@value ConfigRevisionRepository#CHANNEL} for fast propagation, plus a periodic poll of the
 * per-version revisions and the catalog revision as the safety net for notifications missed while reconnecting.
 * <ul>
 *     <li>{@code IG} / {@code IG_EXCLUDE}: re-reads that version's included set and applies it (one pinned context per
 *     included IG, each resolved from the catalog view).</li>
 *     <li>{@code IG_ACCESS} (spec 005): re-reads that version's row and swaps the access of its included contexts;
 *     the included set is unchanged, so nothing is rebuilt.</li>
 *     <li>{@code IG_TERMINOLOGY} (spec 007): re-reads that version's row; the context whose terminology setting changed
 *     is rebuilt with it, the old one serving until the new one is ready.</li>
 *     <li>{@code PACKAGE}: refreshes the catalog view, invalidates contexts and cached packages whose content changed,
 *     then re-applies every version (a changed dependency set rebuilds the affected pinned contexts).</li>
 * </ul>
 * Syncs of one version run one after another; catalog syncs run one after another too.
 */
public class ConfigSync {
    private static final Logger logger = LogManager.getLogger(ConfigSync.class);

    /**
     * All syncs of one version run one after another: {@code tail} completes when the last queued sync has finished.
     * {@code backgroundQueued} coalesces notification/poll syncs while one is still waiting to start.
     */
    private static final class Serial {
        Future<Void> tail = Future.succeededFuture();
        boolean backgroundQueued;
    }

    private final Vertx vertx;
    private final Pool pool;
    private final PgConnectOptions connectOptions;
    private final ValidatorRegistry registry;
    private final PackageCatalog catalog;
    private final PackageCache packageCache;
    private final long pollIntervalMs;
    private final Map<SupportedFhirVersion, Serial> states = new EnumMap<>(SupportedFhirVersion.class);
    private final Serial catalogState = new Serial();
    /**
     * The catalog view contexts were last reconciled with. Tracked here, not read from the catalog, because a lookup
     * miss may refresh the catalog view directly; the next catalog sync must still see what changed since this one.
     */
    private volatile PackageCatalogView reconciled = PackageCatalogView.EMPTY;
    /** Access of the included contexts, per version (spec 005): read without a lock on the validation hot path. */
    private final Map<SupportedFhirVersion, PinnedAccess> pinnedAccess = new java.util.concurrent.ConcurrentHashMap<>();
    private PgSubscriber subscriber;
    private long pollTimer = -1;
    private volatile boolean notificationsEnabled = true;

    public ConfigSync(Vertx vertx, Pool pool, PgConnectOptions connectOptions, ValidatorRegistry registry,
                      long pollIntervalMs) {
        this(vertx, pool, connectOptions, registry, PackageCatalog.of(pool), null, pollIntervalMs);
    }

    /**
     * @param packageCache parsed-package cache to invalidate when stored content changes (may be {@code null})
     */
    public ConfigSync(Vertx vertx, Pool pool, PgConnectOptions connectOptions, ValidatorRegistry registry,
                      PackageCatalog catalog, PackageCache packageCache, long pollIntervalMs) {
        this.vertx = vertx;
        this.pool = pool;
        this.connectOptions = connectOptions;
        this.registry = registry;
        this.catalog = catalog;
        this.packageCache = packageCache;
        this.pollIntervalMs = pollIntervalMs;
        SupportedFhirVersion.enabled().forEach(v -> states.put(v, new Serial()));
        registry.setCatalog(catalog::current);
    }

    public PackageCatalog catalog() {
        return catalog;
    }

    /** The access of {@code version}'s included contexts, as last read from the configuration. */
    public PinnedAccess pinnedAccess(SupportedFhirVersion version) {
        PinnedAccess a = pinnedAccess.get(version);
        return a == null ? PinnedAccess.EMPTY : a;
    }

    /**
     * Loads the catalog view, builds every context from the current configuration (completes when all are built or
     * failed), then starts listening and polling.
     */
    public Future<Void> start() {
        Future<Void> initial = catalog.refresh()
                .compose(view -> ConfigRevisionRepository.current(pool).compose(rows -> {
                    reconciled = view;
                    List<Future<Void>> builds = new ArrayList<>();
                    for (SupportedFhirVersion version : states.keySet()) {
                        ConfigRevisionRepository.ConfigRow row = rows.get(version);
                        long revision = row == null ? 0 : row.revision();
                        List<String> included = row == null ? List.of() : row.included();
                        if (row != null) {
                            pinnedAccess.put(version, row.access());
                        }
                        PinnedAccess access = row == null ? PinnedAccess.EMPTY : row.access();
                        builds.add(registry.applyPinned(version, revision, resolveAll(row, included, view, access))
                                .recover(err -> Future.succeededFuture()));
                    }
                    return Future.join(builds).mapEmpty();
                }));
        startListening();
        pollTimer = vertx.setPeriodic(pollIntervalMs, id -> poll());
        return initial.mapEmpty();
    }

    /**
     * Each included IG's context, resolved against {@code view} (research R3) as the context's owner (spec 006
     * FR-024). The owner comes from the shared configuration, so every instance resolves the same context.
     */
    static Map<String, ResolvedContext> resolveAll(List<String> included, PackageCatalogView view, PinnedAccess access) {
        return resolveAll(null, included, view, access);
    }

    /**
     * As {@link #resolveAll(List, PackageCatalogView, PinnedAccess)}, with each context's terminology setting from
     * {@code row} (spec 007 FR-004): it is part of what the context is built from, so a changed setting rebuilds it.
     */
    static Map<String, ResolvedContext> resolveAll(ConfigRevisionRepository.ConfigRow row, List<String> included,
                                                   PackageCatalogView view, PinnedAccess access) {
        Map<String, ResolvedContext> contexts = new LinkedHashMap<>();
        for (String key : included) {
            ResolvedContext ctx = ResolvedContext.forKey(key, view, access);
            contexts.put(key, row == null ? ctx : ctx.withTerminology(row.terminologyOf(key)));
        }
        return contexts;
    }

    private void startListening() {
        subscriber = PgSubscriber.subscriber(vertx, connectOptions);
        subscriber.reconnectPolicy(retries -> 1_000L);
        subscriber.channel(ConfigRevisionRepository.CHANNEL).handler(this::onNotification);
        subscriber.connect().onFailure(err -> logger.warn("LISTEN connection failed ({}); relying on polling until it reconnects", err.getMessage()));
    }

    private void onNotification(String payload) {
        if (!notificationsEnabled) {
            return;
        }
        try {
            JsonObject message = new JsonObject(payload);
            if (ConfigRevisionRepository.KIND_PACKAGE.equals(message.getString("kind"))) {
                syncCatalog();
                return;
            }
            String version = message.getString("fhirVersion");
            SupportedFhirVersion.parseEnabled(version).ifPresent(this::sync);
        } catch (Exception e) {
            logger.warn("Ignoring malformed configuration notification");
        }
    }

    private void poll() {
        ConfigRevisionRepository.catalogRevision(pool).onSuccess(revision -> {
            if (revision > catalog.current().revision()) {
                syncCatalog();
            }
        });
        ConfigRevisionRepository.current(pool).onSuccess(rows -> rows.forEach((version, row) -> {
            if (states.containsKey(version)) {
                registry.noteSeen(version, row.revision());
                if (row.revision() > registry.appliedRevision(version)) {
                    sync(version);
                }
            }
        })).onFailure(err -> logger.debug("Configuration poll failed: {}", err.getMessage()));
    }

    /**
     * Background sync (notification or poll): brings one version up to the latest configuration. Coalesced: if a
     * background sync is already queued and has not started, this one is dropped (the queued one sees the change).
     */
    public void sync(SupportedFhirVersion version) {
        Serial state = states.get(version);
        synchronized (state) {
            if (state.backgroundQueued) {
                return;
            }
            state.backgroundQueued = true;
        }
        enqueue(state, () -> {
            synchronized (state) {
                state.backgroundQueued = false;
            }
        }, () -> runSync(version)).onFailure(err -> logger.warn("Configuration sync for {} failed: {}", version, err.getMessage()));
    }

    /**
     * One sync run for {@code version}, completing when the latest committed configuration is applied locally. It
     * runs after any sync of the version already in flight, so a caller never returns while another sync is still
     * applying the very change it waits for.
     */
    public Future<Void> syncNow(SupportedFhirVersion version) {
        return enqueue(states.get(version), () -> { }, () -> runSync(version));
    }

    /**
     * Background catalog sync: refreshes the catalog view, invalidates what changed, and re-applies every version.
     * Coalesced like {@link #sync}.
     */
    public void syncCatalog() {
        synchronized (catalogState) {
            if (catalogState.backgroundQueued) {
                return;
            }
            catalogState.backgroundQueued = true;
        }
        enqueue(catalogState, () -> {
            synchronized (catalogState) {
                catalogState.backgroundQueued = false;
            }
        }, this::runCatalogSync).onFailure(err -> logger.warn("Package catalog sync failed: {}", err.getMessage()));
    }

    /** Catalog sync that completes once this instance has applied the latest package catalog. */
    public Future<Void> syncCatalogNow() {
        return enqueue(catalogState, () -> { }, this::runCatalogSync);
    }

    private Future<Void> enqueue(Serial state, Runnable onStart, java.util.function.Supplier<Future<Void>> work) {
        synchronized (state) {
            Future<Void> run = state.tail.transform(previous -> {
                onStart.run();
                return work.get();
            });
            state.tail = run.otherwiseEmpty();
            return run;
        }
    }

    /**
     * Apply the latest revision (which rebuilds only contexts whose packages changed). An included package this
     * instance's catalog view doesn't know yet (stored elsewhere moments ago) refreshes the view first, so the
     * context is resolved with its dependencies and hashes rather than rebuilt once the catalog sync arrives.
     */
    private Future<Void> runSync(SupportedFhirVersion version) {
        return ConfigRevisionRepository.current(pool)
                .compose(rows -> {
                    ConfigRevisionRepository.ConfigRow row = rows.get(version);
                    if (row == null) {
                        return Future.succeededFuture();
                    }
                    // Access first: it takes effect at once, also while a changed context is still being built.
                    PinnedAccess previous = pinnedAccess.put(version, row.access());
                    if (previous != null && !previous.equals(row.access())) {
                        logger.info("Access of included IG validator contexts for {} changed (revision {})", version, row.revision());
                    }
                    boolean unknown = row.included().stream().anyMatch(k -> !catalog.current().contains(k));
                    Future<PackageCatalogView> view = unknown
                            ? catalog.refresh().recover(err -> Future.succeededFuture(catalog.current()))
                            : Future.succeededFuture(catalog.current());
                    return view.compose(v -> registry.applyPinned(version, row.revision(),
                            resolveAll(row, row.included(), v, row.access())));
                });
    }

    private Future<Void> runCatalogSync() {
        return catalog.refresh().compose(refreshed -> {
            // Never reconcile with a view older than the newest one (a lookup miss may have refreshed in between).
            PackageCatalogView view = catalog.current().revision() > refreshed.revision() ? catalog.current() : refreshed;
            PackageCatalogView before = reconciled;
            if (view.revision() >= before.revision()) {
                reconciled = view;
                invalidateChangedPackages(before, view);
            }
            registry.onCatalogChanged(view);
            List<Future<Void>> versions = new ArrayList<>();
            for (SupportedFhirVersion version : states.keySet()) {
                versions.add(syncNow(version).recover(err -> Future.succeededFuture()));
            }
            return Future.join(versions).mapEmpty();
        });
    }

    /** Parsed packages whose stored content changed or disappeared are dropped from the package cache (FR-025). */
    private void invalidateChangedPackages(PackageCatalogView before, PackageCatalogView after) {
        if (packageCache == null) {
            return;
        }
        for (String key : before.keys()) {
            byte[] old = before.sha256(key);
            if (!after.contains(key) || (old != null && !Arrays.equals(old, after.sha256(key)))) {
                packageCache.invalidate(key);
                logger.info("Stored package {} changed; cached copy dropped", key);
            }
        }
    }

    /** Test hook: ignore notifications so only the poll fallback propagates changes. */
    public void setNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
    }

    public Future<Void> stop() {
        if (pollTimer >= 0) {
            vertx.cancelTimer(pollTimer);
            pollTimer = -1;
        }
        return subscriber == null ? Future.succeededFuture() : subscriber.close();
    }
}
