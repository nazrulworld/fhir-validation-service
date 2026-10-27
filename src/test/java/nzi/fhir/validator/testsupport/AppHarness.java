package nzi.fhir.validator.testsupport;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import nzi.fhir.validator.Bootstrap;
import nzi.fhir.validator.core.config.ApplicationConfig;

/**
 * Starts the whole service in-process (same wiring as production) against a test database.
 */
public final class AppHarness {
    /** Bootstrap admin key of every harness ({@code FV_ADMIN_API_KEY}). */
    public static final String ADMIN_KEY = "test-admin-key-0123456789abcdefghijklmnop";
    public static final String ADMIN_AUTH = "Bearer " + ADMIN_KEY;

    private final Vertx vertx;
    private final Bootstrap bootstrap;
    private final WebClient client;

    private AppHarness(Vertx vertx, Bootstrap bootstrap) {
        this.vertx = vertx;
        this.bootstrap = bootstrap;
        this.client = WebClient.create(vertx);
    }

    public static JsonObject baseConfig(String pgHost, int pgPort, String database, String user, String password) {
        return new JsonObject()
                .put("profile", "dev")
                .put("http", new JsonObject().put("port", 0).put("instances", 1))
                .put("validation", new JsonObject().put("workers", 4))
                .put("sync", new JsonObject().put("pollIntervalMs", 1000))
                .put("auth", new JsonObject().put("bootstrapAdminKey", ADMIN_KEY).put("cacheTtlMs", 500))
                .put("shutdown", new JsonObject().put("preDrainMs", 0).put("graceMs", 2000))
                .put("pg", new JsonObject()
                        .put("host", pgHost).put("port", pgPort).put("database", database)
                        .put("user", user).put("password", password).put("sslMode", "disable").put("poolSize", 5));
    }

    public static Future<AppHarness> start(Vertx vertx, JsonObject config) {
        return Bootstrap.start(vertx, ApplicationConfig.of(config)).map(b -> new AppHarness(vertx, b));
    }

    /**
     * Like production ({@code Bootstrap.main}): {@code vertx} reports its HTTP metrics into {@code meterRegistry}, the
     * registry {@code /metrics} serves.
     */
    public static Future<AppHarness> start(Vertx vertx, JsonObject config, PrometheusMeterRegistry meterRegistry) {
        return Bootstrap.start(vertx, ApplicationConfig.of(config), meterRegistry).map(b -> new AppHarness(vertx, b));
    }

    public Bootstrap bootstrap() {
        return bootstrap;
    }

    public int port() {
        return bootstrap.services().boundPort();
    }

    public WebClient client() {
        return client;
    }

    /** Adds the admin key to a request. */
    public static <T> io.vertx.ext.web.client.HttpRequest<T> asAdmin(io.vertx.ext.web.client.HttpRequest<T> request) {
        return request.putHeader("Authorization", ADMIN_AUTH);
    }

    public Future<Void> stop() {
        return bootstrap.stop();
    }

    /**
     * Polls readiness until it reports 200 or the timeout expires.
     */
    public Future<Void> awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        return poll(deadline);
    }

    private Future<Void> poll(long deadline) {
        return client.get(port(), "localhost", "/health/readiness").send()
                .compose(r -> {
                    if (r.statusCode() == 200) {
                        return Future.succeededFuture();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        return Future.failedFuture("not ready: " + r.bodyAsString());
                    }
                    return vertx.timer(200).compose(v -> poll(deadline));
                });
    }
}
