package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD))
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
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
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
                            "fv_config_revision\\{version=\"R4\",?\\} \\d", "fv_package_cache_size_bytes \\d",
                            "fv_profile_count\\{version=\"STU3\",?\\} \\d", "fv_validator_ready\\{version=\"R5\",?\\} 1\\.0")) {
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
