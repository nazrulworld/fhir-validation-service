package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Fixtures;
import nzi.fhir.validator.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-031: Prometheus metrics and correlation ids on request log lines.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ObservabilityIT extends BaseTestContainer {

    private AppHarness app;
    private LogCapture logs;
    private Vertx metricsVertx;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        resetSchema();
        // A Vert.x with metrics enabled, as in production, so the HTTP request-rate family is exported (SC-011).
        PrometheusMeterRegistry meterRegistry = ServiceMetrics.newRegistry();
        metricsVertx = Vertx.vertx(new VertxOptions().setMetricsOptions(new MicrometerMetricsOptions()
                .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                .setMicrometerRegistry(meterRegistry)
                .setEnabled(true)));
        AppHarness.start(metricsVertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                        POSTGRES_USERNAME, POSTGRES_PASSWORD), meterRegistry)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (logs != null) {
            logs.close();
        }
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> metricsVertx == null ? Future.succeededFuture() : metricsVertx.close())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<?> validate() {
        return app.client().post(app.port(), "localhost", "/R4/validate").putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}"));
    }

    @Test
    void metricsAreExposed(VertxTestContext ctx) {
        Future.all(validate(), validate(), validate())
                .compose(v -> app.client().get(app.port(), "localhost", "/metrics").send())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode());
                    assertTrue(r.getHeader("Content-Type").startsWith("text/plain"));
                    String body = r.bodyAsString();
                    // The exposition format may end a label set with a comma: {a="b",}.
                    for (String metric : List.of("fv_validations_total\\{outcome=\"valid\",version=\"R4\",?\\} 3\\.0",
                            "fv_validation_seconds_count\\{version=\"R4\",?\\} 3\\.0", "fv_validation_pending \\d",
                            "fv_admin_pending \\d", "fv_admin_max_pending 4\\.0",
                            "fv_config_revision\\{version=\"R4\",?\\} \\d", "fv_package_cache_size_bytes \\d",
                            "fv_profile_count\\{version=\"STU3\",?\\} \\d", "fv_validator_ready\\{version=\"R5\",?\\} 1\\.0",
                            "fv_snapshot_packages\\{version=\"R4\",?\\} 0\\.0", "fv_snapshot_package_size_bytes\\{version=\"R4\",?\\} 0\\.0",
                            // SC-011: request rate (Vert.x HTTP server metrics) and cache effectiveness.
                            "vertx_http_server_requests_total\\{[^}]*\\} [1-9]", "fv_package_cache_hits_total \\d",
                            "fv_package_cache_misses_total \\d")) {
                        assertTrue(java.util.regex.Pattern.compile(metric).matcher(body).find(), "missing " + metric);
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void requestLogLinesCarryTheCorrelationId(VertxTestContext ctx) {
        logs = LogCapture.start();
        String requestId = "obs-test-0001-request";
        app.bootstrap().services().igPackageService().registerIg(Fixtures.bytes(Fixtures.DK_CORE_IG))
                .compose(p -> AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig"))
                        .putHeader("X-Request-Id", requestId)
                        .sendJsonObject(new JsonObject().put("igPackageId", "hl7.fhir.dk.core").put("igPackageVersion", "3.4.0")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(requestId, r.getHeader("X-Request-Id"));
                    List<LogCapture.Captured> included = logs.events().stream()
                            .filter(e -> e.message().startsWith("Included IG hl7.fhir.dk.core")).toList();
                    assertFalse(included.isEmpty(), "the request logs its outcome");
                    included.forEach(e -> assertEquals(requestId, e.requestId(), "log line without correlation id: " + e.message()));
                    ctx.completeNow();
                })));
    }
}
