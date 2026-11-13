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
 * Process entry point. With a command group as the first argument ({@code org}, {@code key}, {@code package},
 * {@code snapshot}) it runs the administrative command line and exits (spec 005); otherwise it starts the service. The
 * HTTP verticles start first (liveness answers immediately, readiness stays false), then
 * the database is migrated (retrying until reachable) and the validators are built from the shared configuration.
 */
public final class Bootstrap {
    /**
     * Created on first use, not when the class loads: the command line must choose its Log4j configuration (stderr
     * only) before Log4j initialises (spec 005, contracts/cli.md).
     */
    private static final class Log {
        static final Logger LOGGER = LogManager.getLogger(Bootstrap.class);
    }

    private static Logger logger() {
        return Log.LOGGER;
    }

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
    /** Set by a fatal startup error: the shutdown hook then closes Vert.x without the graceful drain. */
    static final java.util.concurrent.atomic.AtomicBoolean FATAL = new java.util.concurrent.atomic.AtomicBoolean();

    public static void main(String[] args) {
        // Spec 005: "org|key|package|snapshot ..." runs one administrative command instead of the server.
        if (nzi.fhir.validator.cli.AdminCli.isCommand(args)) {
            // Before anything logs: stdout must carry only the command's JSON. Whatever else writes to System.out
            // (libraries, JVM warnings) goes to stderr; the result goes to the real stdout.
            java.io.PrintStream stdout = System.out;
            System.setOut(System.err);
            nzi.fhir.validator.cli.AdminCli.useStderrLogging();
            int exit = nzi.fhir.validator.cli.AdminCli.run(args, stdout, System.err, ConfigLoader.environment());
            LogManager.shutdown();
            System.exit(exit);
            return;
        }
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
                    logger().info("FHIR validation service started ({} HTTP instance(s))", config.httpInstances());
                    // Spec 008 (research R14): a fatal warm-up error stops the process; others leave it unready.
                    b.warmUp().onFailure(err -> exitOnFatal(err, System::exit));
                })
                .onFailure(err -> {
                    logger().error("Failed to start FHIR validation service", err);
                    // Never close Vert.x from one of its own callbacks (it never completes): exit from another
                    // thread; the shutdown hook closes Vert.x.
                    exitFromOwnThread(System::exit, 1);
                });
    }

    /**
     * Exits with status 1 when {@code err} is a {@link nzi.fhir.validator.core.StartupException}. The exit runs on its
     * own thread: this is called from a Vert.x callback, and closing Vert.x from one of its own callbacks never
     * completes. {@code System.exit} runs the shutdown hook, which drains and closes Vert.x from a non-Vert.x thread.
     *
     * @return the exiting thread, or {@code null} when {@code err} isn't fatal
     */
    static Thread exitOnFatal(Throwable err, java.util.function.IntConsumer exit) {
        if (!(err instanceof nzi.fhir.validator.core.StartupException)) {
            return null;
        }
        logger().error("Startup failed: {}", err.getMessage(), err);
        // The instance never became ready: no pre-drain window or grace period, just stop.
        FATAL.set(true);
        return exitFromOwnThread(exit, 1);
    }

    /**
     * Calls {@code exit} with {@code status} on a new thread, so a Vert.x callback can end the process: closing Vert.x
     * from one of its own callbacks never completes, while {@code System.exit} runs the shutdown hook, which drains and
     * closes Vert.x from a non-Vert.x thread.
     */
    static Thread exitFromOwnThread(java.util.function.IntConsumer exit, int status) {
        Thread t = new Thread(() -> exit.accept(status), "fv-exit");
        t.start();
        return t;
    }

    /**
     * JVM shutdown hook (SIGTERM): drain, stop, close Vert.x. Waits on a latch, never on a future (Principle I).
     */
    private static void shutdown(Vertx vertx, ApplicationConfig config) {
        Bootstrap b = FATAL.get() ? null : running;
        long timeoutMs = config.shutdownPreDrainMs() + config.shutdownGraceMs() + 5_000;
        try {
            if (b != null) {
                CountDownLatch stopped = new CountDownLatch(1);
                b.stop().onComplete(ar -> stopped.countDown());
                if (!stopped.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    logger().warn("Drain did not finish in time");
                }
            }
            // Close Vert.x from this (non-Vert.x) thread: closing it from one of its own callbacks never completes.
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            closed.await(5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Log4j's own shutdown hook is disabled (log4j2.xml) so the drain is logged; stop it last.
            LogManager.shutdown();
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
                    // Spec 009 FR-015 (contracts/operations.md §3).
                    nzi.fhir.validator.core.enums.ServedVersions served = config.servedVersions();
                    logger().info("FHIR versions served: {} ({})", served.label(),
                            served.isConfigured() ? "FV_ENABLED_FHIR_VERSIONS" : "all; FV_ENABLED_FHIR_VERSIONS not set");
                    Future<Void> warmUp = DatabaseService.migrate(vertx, executors, PgConfig.createPgOptions(config))
                            .compose(v -> services.organisations().ensureWellKnown())
                            .compose(v -> installSystemOrganisation(services))
                            // Spec 009 (research R11): the access-control mode, the fixed callers' keys and the gate.
                            .compose(v -> nzi.fhir.validator.core.security.AclModeStartup.run(services.pool(),
                                    services.apiKeys(), services.config(), services.auth()))
                            // Spec 009 FR-010 to FR-014: stored contexts follow FV_ENABLED_FHIR_VERSIONS before any is built.
                            .compose(v -> nzi.fhir.validator.core.validator.ContextActivation.reconcile(services.pool(),
                                    services.config().servedVersions()).mapEmpty())
                            .compose(v -> services.startTerminology())
                            .compose(v -> services.configSync().start())
                            .onSuccess(v -> logger().info("Validators initialised: {}", services.validatorRegistry().details().encode()))
                            .onFailure(err -> logger().error("Validator initialisation failed", err));
                    return new Bootstrap(vertx, services, id, warmUp);
                });
    }

    /**
     * Applies the configured {@code FV_SYSTEM_ORG_*} fields to the system organisation (spec 005 FR-004, research
     * R10); fields that aren't configured keep their stored values. Logs the field names, never their values.
     */
    private static Future<Void> installSystemOrganisation(ServiceRegistry services) {
        java.util.Map<String, Object> fields = services.config().systemOrganisation();
        if (fields.isEmpty()) {
            return Future.succeededFuture();
        }
        return services.organisations().applySystemConfig(fields)
                .onSuccess(o -> logger().info("System organisation configured: {}", fields.keySet()))
                .mapEmpty();
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
     * then refuse new requests (503) while in-flight ones finish, up to {@code FV_SHUTDOWN_GRACE_MS}, then close the
     * HTTP servers and release connections and pools.
     */
    public Future<Void> stop() {
        ApplicationConfig config = services.config();
        services.healthService().setDraining(true);
        logger().info("drain started ({} request(s) in flight)", services.inFlight().get());
        Future<Void> preDrain = config.shutdownPreDrainMs() > 0 ? vertx.timer(config.shutdownPreDrainMs()).mapEmpty() : Future.succeededFuture();
        return preDrain
                .compose(v -> {
                    // Load balancers have had the pre-drain window to notice; from now on no new work is accepted.
                    services.refusingNewRequests().set(true);
                    return awaitIdle(System.currentTimeMillis() + config.shutdownGraceMs());
                })
                .onComplete(ar -> logger().info("drain complete ({} request(s) still in flight)", services.inFlight().get()))
                // Spec 008 FR-018: buffered usage records are written before the pool closes.
                .eventually(() -> services.usage().close())
                .eventually(() -> services.stop())
                .eventually(() -> vertx.undeploy(deploymentId))
                .eventually(() -> services.pool().close())
                .eventually(() -> services.executors().close());
    }
}
