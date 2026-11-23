package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.security.Caller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.LongSupplier;

/**
 * Spec 014: the rate limits of one instance. Holds the current cap snapshot (reloaded every
 * {@code FV_THROTTLE_CAPS_REFRESH_MS}) and the {@link Throttle}; built and started only while access control is enabled
 * (FR-002). Until the first snapshot is loaded, and (T056) its peers have counted it, it is not {@link #ready()}: the
 * handler answers 503 and readiness
 * reports it (constitution VII).
 */
public final class ThrottleService {
    private static final Logger logger = LogManager.getLogger(ThrottleService.class);
    private static final long WARN_INTERVAL_MS = 60_000;

    private final Vertx vertx;
    private final Pool pool;
    private final long capsRefreshMs;
    private final Throttle throttle;
    private final long syncIntervalMs;
    private ThrottleSync sync;
    private long syncTimer = -1;
    private volatile ThrottleCaps caps = ThrottleCaps.EMPTY;
    /** The first cap snapshot is loaded. */
    private volatile boolean ready;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private long capsTimer = -1;
    private long lastCapsWarn = Long.MIN_VALUE;
    private Runnable capsFailureListener = () -> {
    };

    public ThrottleService(Vertx vertx, Pool pool, ApplicationConfig config) {
        this.vertx = vertx;
        this.pool = pool;
        this.capsRefreshMs = config.throttleCapsRefreshMs();
        this.throttle = new Throttle(config.throttleMaxTracked(), config.throttleSyncIntervalMs());
        this.syncIntervalMs = config.throttleSyncIntervalMs();
    }

    /**
     * Loads the caps, reads the other instances' counts of the last 60 s (research R4) and starts sharing counts every
     * {@code FV_THROTTLE_SYNC_INTERVAL_MS} and reloading caps every {@code FV_THROTTLE_CAPS_REFRESH_MS}. Completes once
     * the instance is ready: after two sync ticks, when every peer counts it (T056).
     */
    public Future<Void> start() {
        sync = new ThrottleSync(throttle, new ThrottleCountsRepository(pool), syncIntervalMs, () -> clock.getAsLong(),
                nzi.fhir.validator.core.settings.ServiceSettingsRepository.instanceName());
        sync.listener(syncListener);
        return ThrottleCapsRepository.load(pool).compose(loaded -> sync.start().map(loaded)).map(loaded -> {
            caps = loaded;
            ready = true;
            syncTimer = vertx.setPeriodic(syncIntervalMs, id -> sync.tick());
            logger.info("Rate limits: default key {}/min, default organisation {}/min; {} key cap(s), {} organisation cap(s)",
                    label(loaded.defaultKey()), label(loaded.defaultOrg()), loaded.keyCaps().size(), loaded.orgCaps().size());
            capsTimer = vertx.setPeriodic(capsRefreshMs, id -> reloadCaps());
            return (Void) null;
        })
                // T056: complete (and so the service's warm-up) only once every peer counts this instance, as readiness.
                .compose(v -> sync.warmed());
    }

    private static String label(java.util.OptionalInt cap) {
        return cap.isPresent() ? String.valueOf(cap.getAsInt()) : "none";
    }

    /** Reloads the caps now (tests, and the periodic reload); a failure keeps the last snapshot. */
    public Future<Void> reload() {
        return reloadCaps();
    }

    /** FR-014: a failed reload keeps the last snapshot (WARN at most once a minute). */
    private Future<Void> reloadCaps() {
        return ThrottleCapsRepository.load(pool).onSuccess(loaded -> caps = loaded).onFailure(err -> {
            capsFailureListener.run();
            long now = System.currentTimeMillis();
            if (lastCapsWarn == Long.MIN_VALUE || now - lastCapsWarn >= WARN_INTERVAL_MS) {
                lastCapsWarn = now;
                logger.warn("throttle: cannot reload rate limits ({}); keeping the snapshot from {}",
                        err.getMessage(), caps.loadedAt());
            }
        }).mapEmpty();
    }

    /** Stops reloading and sharing; removes this instance's heartbeat so peers stop reserving room for it. */
    public Future<Void> stop() {
        if (capsTimer >= 0) {
            vertx.cancelTimer(capsTimer);
            capsTimer = -1;
        }
        if (syncTimer >= 0) {
            vertx.cancelTimer(syncTimer);
            syncTimer = -1;
        }
        return sync == null ? Future.succeededFuture() : sync.stop();
    }

    /** The count sharing (null before {@link #start()}). */
    public ThrottleSync sync() {
        return sync;
    }

    private ThrottleSync.Listener syncListener = ThrottleSync.Listener.NONE;

    /** Metrics hooks of the count sharing; set before {@link #start()}. */
    public void onSync(ThrottleSync.Listener listener) {
        this.syncListener = listener;
        if (sync != null) {
            sync.listener(listener);
        }
    }

    /** Checks and counts one validation request of {@code caller} (a key outside the system organisation). */
    public ThrottleDecision check(Caller caller) {
        ThrottleDecision d = throttle.check(caller.apiKeyId(), caller.orgId(), caps, clock.getAsLong());
        if (d.limited()) {
            metrics.checked();
        }
        return d;
    }

    /** Metrics hooks of the request path (ServiceMetrics.bindThrottle). */
    public interface Metrics {
        void checked();

        void refused(ThrottleDecision.Scope scope, ThrottleDecision.Reason reason);

        Metrics NONE = new Metrics() {
            @Override
            public void checked() {
            }

            @Override
            public void refused(ThrottleDecision.Scope scope, ThrottleDecision.Reason reason) {
            }
        };
    }

    private volatile Metrics metrics = Metrics.NONE;

    public void onDecision(Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Records one refused request: the refusal metric (FR-017) and the bounded refusal log (FR-018), at most one line
     * per subject per minute; later refusals in that minute are counted and reported by the next line.
     */
    public void refused(Caller caller, ThrottleDecision decision, String requestId) {
        metrics.refused(decision.scope(), decision.reason());
        boolean key = decision.scope() == ThrottleDecision.Scope.KEY;
        java.util.UUID subject = key ? caller.apiKeyId() : caller.orgId();
        if (subject == null) {
            return;
        }
        Window w = throttle.window(key ? Throttle.KEY : Throttle.ORG, subject);
        Window.LogLine line = w.refusal(clock.getAsLong(), requestId);
        if (line != null) {
            logger.info("throttle: {} {} (org {}) refused {} request(s) in the last minute (cap {}); request {}",
                    decision.scope().label(), subject, caller.orgId(), line.refusals(), decision.limit(), line.firstRequestId());
        }
    }

    /** Readiness (constitution VII): {@code others}, plus {@code rate limits not loaded} until the first snapshot is in. */
    public java.util.List<String> notReadyReasons(java.util.List<String> others) {
        if (ready()) {
            return others;
        }
        java.util.List<String> all = new java.util.ArrayList<>(others);
        all.add(NOT_LOADED);
        return all;
    }

    /** The readiness reason while the first cap snapshot is not loaded. */
    public static final String NOT_LOADED = "rate limits not loaded";

    /** Whether the first cap snapshot has been loaded. */
    public boolean ready() {
        // T056: also not before every peer counts this instance (two sync ticks), so a join can't exceed the bound.
        return ready && sync != null && sync.warm();
    }

    public ThrottleCaps caps() {
        return caps;
    }

    public Throttle throttle() {
        return throttle;
    }

    /** The current time in epoch milliseconds, as the throttle sees it. */
    public long now() {
        return clock.getAsLong();
    }

    /** Tests only: the clock (epoch ms) every check reads. */
    public void clock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Called on every failed cap reload (metrics). */
    public void onCapsFailure(Runnable listener) {
        this.capsFailureListener = listener;
    }
}
