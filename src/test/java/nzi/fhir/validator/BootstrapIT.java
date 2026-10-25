package nzi.fhir.validator;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-process smoke test of the production wiring: migrate, build validators, serve HTTP.
 */
@ExtendWith(VertxExtension.class)
class BootstrapIT extends BaseTestContainer {

    private AppHarness app;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD))
                .onComplete(ctx.succeeding(a -> {
                    app = a;
                    ctx.completeNow();
                }));
    }

    @AfterEach
    void stop(VertxTestContext ctx) {
        if (app == null) {
            ctx.completeNow();
            return;
        }
        app.stop().onComplete(ar -> ctx.completeNow());
    }

    @Test
    @Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void becomesReadyAndValidates(Vertx vertx, VertxTestContext ctx) {
        app.awaitReady(90_000)
                .compose(v -> app.client().post(app.port(), "localhost", "/R4/validate")
                        .putHeader("Content-Type", "application/fhir+json")
                        .putHeader("Accept", "application/json")
                        .sendBuffer(Buffer.buffer(Fixtures.string(Fixtures.DK_PATIENT))))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertNotNull(r.bodyAsJsonObject().getBoolean("valid"));
                    assertNotNull(r.getHeader("X-Request-Id"));
                    ctx.completeNow();
                })));
    }

    @Test
    void livenessAnswersAndUnknownRouteIsProblem(Vertx vertx, VertxTestContext ctx) {
        app.client().get(app.port(), "localhost", "/health/liveness").send()
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode()));
                    return app.client().get(app.port(), "localhost", "/no/such/route").send();
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(404, r.statusCode());
                    assertTrue(r.getHeader("Content-Type").startsWith("application/problem+json"));
                    ctx.completeNow();
                })));
    }
}
