package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F7 regression / FR-029: on stop, readiness turns 503 first, in-flight requests complete, then the server closes.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class GracefulShutdownIT extends BaseTestContainer {

    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("shutdown", new JsonObject().put("preDrainMs", 500).put("graceMs", 30_000));
        AppHarness.start(vertx, config)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void inFlightRequestCompletesWhileReadinessReportsDraining(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean sawDraining = new AtomicBoolean();
        Future<HttpResponse<Buffer>> slow = app.client().post(app.port(), "localhost", "/R4/validate").timeout(120_000)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer(TestPackages.patientBundle(8000)));
        int port = app.port();
        vertx.timer(300)
                .compose(t -> {
                    ctx.verify(() -> assertTrue(app.bootstrap().services().inFlight().get() >= 1, "the slow request is in flight"));
                    Future<Void> stopped = app.stop();
                    // During the pre-drain window the server still answers, with readiness 503.
                    app.client().get(port, "localhost", "/health/readiness").send()
                            .onSuccess(r -> sawDraining.set(r.statusCode() == 503 && r.bodyAsString().contains("draining")));
                    // After the pre-drain, while the slow request is still running, new work is refused.
                    Future<Boolean> refusedDuringGrace = vertx.timer(1_000).compose(t2 -> {
                        ctx.verify(() -> assertTrue(app.bootstrap().services().inFlight().get() >= 1,
                                "the slow request is still in flight during the grace period"));
                        return app.client().get(port, "localhost", "/health/liveness").timeout(2_000).send()
                                .map(r -> r.statusCode() == 503);
                    });
                    return Future.all(slow, stopped, refusedDuringGrace).onSuccess(all ->
                            ctx.verify(() -> assertTrue(refusedDuringGrace.result(), "new requests during the grace period are refused")));
                })
                .compose(all -> {
                    HttpResponse<Buffer> response = slow.result();
                    ctx.verify(() -> {
                        assertEquals(200, response.statusCode(), response.bodyAsString());
                        assertTrue(sawDraining.get(), "readiness must report draining during pre-drain");
                    });
                    return app.client().get(port, "localhost", "/health/liveness").timeout(2_000).send()
                            .map(r -> false).recover(err -> Future.succeededFuture(true));
                })
                .onComplete(ctx.succeeding(refused -> ctx.verify(() -> {
                    assertTrue(refused, "after stop, new connections are refused");
                    app = null;
                    ctx.completeNow();
                })));
    }
}
