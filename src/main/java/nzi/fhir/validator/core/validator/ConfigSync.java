package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.pubsub.PgSubscriber;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps this instance's validators in line with the shared configuration (research R4): a {@code LISTEN} on
 * {@value ConfigRevisionRepository#CHANNEL} for fast propagation, plus a periodic revision poll as the safety net
 * for notifications missed while reconnecting. Both paths run the same per-version sync, serialised per version.
 */
public class ConfigSync {
    private static final Logger logger = LogManager.getLogger(ConfigSync.class);

    /**
     * All syncs of one version run one after another: {@code tail} completes when the last queued sync has finished.
     * {@code backgroundQueued} coalesces notification/poll syncs while one is still waiting to start.
     */
    private static final class VersionState {
        Future<Void> tail = Future.succeededFuture();
        boolean backgroundQueued;
    }

    private final Vertx vertx;
    private final Pool pool;
    private final PgConnectOptions connectOptions;
    private final ValidatorRegistry registry;
    private final long pollIntervalMs;
    private final Map<SupportedFhirVersion, VersionState> states = new EnumMap<>(SupportedFhirVersion.class);
    private PgSubscriber subscriber;
    private long pollTimer = -1;
    private volatile boolean notificationsEnabled = true;

    public ConfigSync(Vertx vertx, Pool pool, PgConnectOptions connectOptions, ValidatorRegistry registry,
                      long pollIntervalMs) {
        this.vertx = vertx;
        this.pool = pool;
        this.connectOptions = connectOptions;
        this.registry = registry;
        this.pollIntervalMs = pollIntervalMs;
        SupportedFhirVersion.enabled().forEach(v -> states.put(v, new VersionState()));
    }

    /**
     * Builds every validator from the current configuration (completes when all are built or failed), then starts
     * listening and polling.
     */
    public Future<Void> start() {
        Future<Void> initial = ConfigRevisionRepository.current(pool)
                .compose(rows -> {
                    List<Future<Void>> builds = new ArrayList<>();
                    rows.forEach((version, row) -> {
                        if (states.containsKey(version)) {
                            builds.add(registry.apply(version, row.revision(), row.resolved())
                                    .recover(err -> Future.succeededFuture()));
                        }
                    });
                    return Future.join(builds).mapEmpty();
                });
        startListening();
        pollTimer = vertx.setPeriodic(pollIntervalMs, id -> poll());
        return initial.mapEmpty();
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
            String version = new JsonObject(payload).getString("fhirVersion");
            SupportedFhirVersion.parseEnabled(version).ifPresent(this::sync);
        } catch (Exception e) {
            logger.warn("Ignoring malformed configuration notification");
        }
    }

    private void poll() {
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
        VersionState state = states.get(version);
        synchronized (state) {
            if (state.backgroundQueued) {
                return;
            }
            state.backgroundQueued = true;
        }
        enqueue(version, () -> {
            synchronized (state) {
                state.backgroundQueued = false;
            }
        }).onFailure(err -> logger.warn("Configuration sync for {} failed: {}", version, err.getMessage()));
    }

    /**
     * One sync run for {@code version}, completing when the latest committed configuration is applied locally. It
     * runs after any sync of the version already in flight, so a caller never returns while another sync is still
     * applying the very change it waits for.
     */
    public Future<Void> syncNow(SupportedFhirVersion version) {
        return enqueue(version, () -> { });
    }

    private Future<Void> enqueue(SupportedFhirVersion version, Runnable onStart) {
        VersionState state = states.get(version);
        synchronized (state) {
            Future<Void> run = state.tail.transform(previous -> {
                onStart.run();
                return runSync(version);
            });
            state.tail = run.otherwiseEmpty();
            return run;
        }
    }

    /** Apply the latest revision (which rebuilds only if the resolved package set changed). */
    private Future<Void> runSync(SupportedFhirVersion version) {
        return ConfigRevisionRepository.current(pool)
                .compose(rows -> {
                    ConfigRevisionRepository.ConfigRow row = rows.get(version);
                    return row == null ? Future.succeededFuture() : registry.apply(version, row.revision(), row.resolved());
                });
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
