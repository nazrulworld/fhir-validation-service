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
                            "fv_validator_ready\\{version=\"R5\",?\\} 1\\.0",
                            "fv_snapshot_packages\\{version=\"R4\",?\\} 0\\.0", "fv_snapshot_package_size_bytes\\{version=\"R4\",?\\} 0\\.0",
                            // SC-011: request rate (Vert.x HTTP server metrics) and cache effectiveness.
                            "vertx_http_server_requests_total\\{[^}]*\\} [1-9]", "fv_package_cache_hits_total \\d",
                            "fv_package_cache_misses_total \\d")) {
                        assertTrue(java.util.regex.Pattern.compile(metric).matcher(body).find(), "missing " + metric);
                    }
                    // Custom profile registration is gone in 2.0.0 (spec 003, SC-008).
                    assertFalse(body.contains("fv_profile_count"), "fv_profile_count must no longer be exposed");
                    ctx.completeNow();
                })));
    }

    @Test
    void unresolvedRequestedProfileIsCountedAsUnknownProfile(VertxTestContext ctx) {
        // Spec 003 (T026, Principle VII): a 400 unknown-profile is recorded with its own outcome label, end to end.
        app.client().post(app.port(), "localhost", "/R4/validate")
                .addQueryParam("profile", "http://example.org/StructureDefinition/typo")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}"))
                .compose(r -> {
                    assertEquals(400, r.statusCode());
                    assertTrue(r.bodyAsJsonObject().getString("type").endsWith("/unknown-profile"));
                    return app.client().get(app.port(), "localhost", "/metrics").send();
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode());
                    assertTrue(java.util.regex.Pattern
                                    .compile("fv_validations_total\\{outcome=\"unknown_profile\",version=\"R4\",?\\} 1\\.0")
                                    .matcher(r.bodyAsString()).find(),
                            "missing fv_validations_total{outcome=\"unknown_profile\",version=\"R4\"}");
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

    /** Spec 004 FR-033: context gauges, builds, evictions, lookups and the package_not_found outcome are exported. */
    @Test
    void contextMetricsAreExposed(VertxTestContext ctx) {
        var services = app.bootstrap().services();
        nzi.fhir.validator.testsupport.TestPackages.storePackage(services,
                        nzi.fhir.validator.testsupport.TestPackages.profilePackage("obs.ig", "1.0.0",
                                nzi.fhir.validator.testsupport.TestPackages.requiredPatientElements(
                                        "http://example.org/fhir/StructureDefinition/obs", "birthDate")))
                .compose(v -> validateIg("obs.ig#1.0.0"))
                .compose(r -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    return validateIg("obs.ig#1.0.0");
                })
                .compose(r -> {
                    assertTrue(services.validatorRegistry().evictOne(null), "evict the on-demand context");
                    return validateIg("not.stored#1.0.0");
                })
                .compose(r -> {
                    assertEquals(404, r.statusCode());
                    return app.client().get(app.port(), "localhost", "/metrics").send();
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    String body = r.bodyAsString();
                    for (String metric : List.of(
                            "fv_validator_contexts\\{fhir_version=\"R4\",kind=\"base\",?\\} 1\\.0",
                            "fv_validator_contexts\\{fhir_version=\"R4\",kind=\"on-demand\",?\\} 0\\.0",
                            "fv_validator_contexts\\{fhir_version=\"R4\",kind=\"pinned\",?\\} 0\\.0",
                            "fv_validator_context_builds_total\\{fhir_version=\"R4\",kind=\"on-demand\",outcome=\"success\",?\\} 1\\.0",
                            "fv_validator_context_evictions_total\\{fhir_version=\"R4\",reason=\"memory\",?\\} 1\\.0",
                            "fv_validator_context_lookups_total\\{fhir_version=\"R4\",result=\"built\",?\\} 1\\.0",
                            "fv_validator_context_lookups_total\\{fhir_version=\"R4\",result=\"hit\",?\\} [1-9]",
                            "fv_validations_total\\{outcome=\"package_not_found\",version=\"R4\",?\\} 1\\.0")) {
                        assertTrue(java.util.regex.Pattern.compile(metric).matcher(body).find(), "missing " + metric);
                    }
                    ctx.completeNow();
                })));
    }

    private Future<io.vertx.ext.web.client.HttpResponse<Buffer>> validateIg(String ig) {
        return app.client().post(app.port(), "localhost", "/R4/validate").addQueryParam("ig", ig)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}"));
    }

    /**
     * Convergence T068 (Constitution VII): log lines of an on-demand build carry the id of the request that started it,
     * also when the build waited in the build queue behind another request's build (default: one build at a time).
     */
    @Test
    void onDemandBuildLogLinesCarryTheStartingRequestsId(VertxTestContext ctx) {
        var services = app.bootstrap().services();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        Future.all(store("obs.first"), store("obs.queued"))
                .compose(v -> {
                    logs = LogCapture.start();
                    services.snapshotBuilder().setBuildHook(() -> {
                        if (first.getAndSet(false)) {
                            try {
                                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                    Future<io.vertx.ext.web.client.HttpResponse<Buffer>> a = validateIg("obs.first#1.0.0", "req-first-build");
                    Future<io.vertx.ext.web.client.HttpResponse<Buffer>> b =
                            metricsVertx.timer(300).compose(t -> validateIg("obs.queued#1.0.0", "req-queued-build"));
                    metricsVertx.setTimer(800, t -> release.countDown());
                    return Future.all(a, b);
                })
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    services.snapshotBuilder().setBuildHook(null);
                    for (int i = 0; i < 2; i++) {
                        assertEquals(200, ((io.vertx.ext.web.client.HttpResponse<?>) all.resultAt(i)).statusCode());
                    }
                    List<String> firstIds = builtLogIds("R4:obs.first#1.0.0");
                    List<String> queuedIds = builtLogIds("R4:obs.queued#1.0.0");
                    assertFalse(firstIds.isEmpty() || queuedIds.isEmpty(), firstIds + " / " + queuedIds);
                    assertTrue(firstIds.stream().allMatch("req-first-build"::equals), firstIds.toString());
                    assertTrue(queuedIds.stream().allMatch("req-queued-build"::equals),
                            "the queued build logs the request that started it, not the one whose build finished first: " + queuedIds);
                    ctx.completeNow();
                })));
    }

    private Future<Void> store(String name) {
        return nzi.fhir.validator.testsupport.TestPackages.storePackage(app.bootstrap().services(),
                nzi.fhir.validator.testsupport.TestPackages.profilePackage(name, "1.0.0",
                        nzi.fhir.validator.testsupport.TestPackages.requiredPatientElements(
                                "http://example.org/fhir/StructureDefinition/" + name, "birthDate")));
    }

    private List<String> builtLogIds(String key) {
        return logs.events().stream().filter(e -> e.message().startsWith("Built validator for " + key + " "))
                .map(LogCapture.Captured::requestId).toList();
    }

    private Future<io.vertx.ext.web.client.HttpResponse<Buffer>> validateIg(String ig, String requestId) {
        return app.client().post(app.port(), "localhost", "/R4/validate").addQueryParam("ig", ig)
                .putHeader("X-Request-Id", requestId).putHeader("Content-Type", "application/json").timeout(30_000)
                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}"));
    }
}
