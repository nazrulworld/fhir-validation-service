package nzi.fhir.validator;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.metrics.ServiceMetrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.config.ConfigLoader;
import nzi.fhir.validator.core.config.PgConfig;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.web.HttpVerticle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Process entry point. The HTTP verticles start first (liveness answers immediately, readiness stays false), then
 * the database is migrated (retrying until reachable) and the validators are built from the shared configuration.
 */
public final class Bootstrap {
    private static final Logger logger = LogManager.getLogger(Bootstrap.class);

    private final Vertx vertx;
    private final ServiceRegistry services;
    private final String deploymentId;
    private final Future<Void> warmUp;

    private Bootstrap(Vertx vertx, ServiceRegistry services, String deploymentId, Future<Void> warmUp) {
        this.vertx = vertx;
        this.services = services;
        this.deploymentId = deploymentId;
        this.warmUp = warmUp;
    }

    private static volatile Bootstrap running;

    public static void main(String[] args) {
        ApplicationConfig config;
        try {
            config = ConfigLoader.load();
        } catch (IllegalStateException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        PrometheusMeterRegistry meterRegistry = ServiceMetrics.newRegistry();
        // Vert.x HTTP server, client and pool metrics report into the same registry as the service's own meters.
        Vertx vertx = Vertx.vertx(new VertxOptions().setMetricsOptions(new MicrometerMetricsOptions()
                .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                .setMicrometerRegistry(meterRegistry)
                .setEnabled(true)));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, config), "fv-shutdown"));
        start(vertx, config, meterRegistry)
                .onSuccess(b -> {
                    running = b;
                    logger.info("FHIR validation service started ({} HTTP instance(s))", config.httpInstances());
                })
                .onFailure(err -> {
                    logger.error("Failed to start FHIR validation service", err);
                    vertx.close().onComplete(v -> System.exit(1));
                });
    }

    /**
     * JVM shutdown hook (SIGTERM): drain, stop, close Vert.x. Waits on a latch, never on a future (Principle I).
     */
    private static void shutdown(Vertx vertx, ApplicationConfig config) {
        Bootstrap b = running;
        long timeoutMs = config.shutdownPreDrainMs() + config.shutdownGraceMs() + 5_000;
        try {
            if (b != null) {
                CountDownLatch stopped = new CountDownLatch(1);
                b.stop().onComplete(ar -> stopped.countDown());
                if (!stopped.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    logger.warn("Drain did not finish in time");
                }
            }
            // Close Vert.x from this (non-Vert.x) thread: closing it from one of its own callbacks never completes.
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            closed.await(5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Completes once HTTP is listening; validators keep building in the background (see {@link #warmUp()}).
     */
    public static Future<Bootstrap> start(Vertx vertx, ApplicationConfig config) {
        return start(vertx, config, ServiceMetrics.newRegistry());
    }

    public static Future<Bootstrap> start(Vertx vertx, ApplicationConfig config, PrometheusMeterRegistry meterRegistry) {
        Executors executors = Executors.create(vertx, config);
        Pool pool = PgConfig.createPgPool(vertx, config);
        HealthService healthService = new HealthService(vertx, pool, config.healthDbStalenessMs()).start();
        ServiceRegistry services = ServiceRegistry.create(vertx, config, pool, executors, healthService, meterRegistry);

        return vertx.deployVerticle(() -> new HttpVerticle(services), new DeploymentOptions().setInstances(config.httpInstances()))
                .map(id -> {
                    Future<Void> warmUp = DatabaseService.migrate(vertx, executors, PgConfig.createPgOptions(config))
                            .compose(v -> installBootstrapKey(services))
                            .compose(v -> services.configSync().start())
                            .onSuccess(v -> logger.info("Validators initialised: {}", services.validatorRegistry().details().encode()))
                            .onFailure(err -> logger.error("Validator initialisation failed", err));
                    return new Bootstrap(vertx, services, id, warmUp);
                });
    }

    private static Future<Void> installBootstrapKey(ServiceRegistry services) {
        String key = services.config().bootstrapAdminKey();
        if (key == null || key.isBlank()) {
            logger.warn("FV_ADMIN_API_KEY is not set: administrative operations are refused");
            return Future.succeededFuture();
        }
        return services.apiKeys().upsertBootstrap(key).onSuccess(v -> logger.info("Bootstrap admin API key installed"));
    }

    private Future<Void> awaitIdle(long deadline) {
        if (services.inFlight().get() == 0 || System.currentTimeMillis() >= deadline) {
            return Future.succeededFuture();
        }
        return vertx.timer(50).compose(v -> awaitIdle(deadline));
    }

    public ServiceRegistry services() {
        return services;
    }

    /** Completes when the initial migration and validator builds have finished. */
    public Future<Void> warmUp() {
        return warmUp;
    }

    /**
     * Graceful stop (FR-029): readiness turns 503, wait {@code FV_SHUTDOWN_PRE_DRAIN_MS} so load balancers notice,
     * wait for in-flight requests up to {@code FV_SHUTDOWN_GRACE_MS}, then close the HTTP servers and release
     * connections and pools.
     */
    public Future<Void> stop() {
        ApplicationConfig config = services.config();
        services.healthService().setDraining(true);
        logger.info("drain started ({} request(s) in flight)", services.inFlight().get());
        Future<Void> preDrain = config.shutdownPreDrainMs() > 0 ? vertx.timer(config.shutdownPreDrainMs()).mapEmpty() : Future.succeededFuture();
        return preDrain
                .compose(v -> awaitIdle(System.currentTimeMillis() + config.shutdownGraceMs()))
                .onComplete(ar -> logger.info("drain complete ({} request(s) still in flight)", services.inFlight().get()))
                .eventually(() -> services.stop())
                .eventually(() -> vertx.undeploy(deploymentId))
                .eventually(() -> services.pool().close())
                .eventually(() -> services.executors().close());
    }
}
