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
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-020: a request that does not complete within FV_HTTP_REQUEST_TIMEOUT_MS is answered with a 503 problem.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class RequestTimeoutIT extends BaseTestContainer {

    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("http").put("requestTimeoutMs", 1_000);
        AppHarness.start(vertx, config)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    @Test
    void slowValidationIsAnsweredWith503AtTheTimeout(VertxTestContext ctx) {
        long start = System.currentTimeMillis();
        app.client().post(app.port(), "localhost", "/R4/validate").timeout(60_000)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer(TestPackages.patientBundle(8000)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 503, "not-ready");
                    assertTrue(p.getString("detail").contains("did not complete"), p.getString("detail"));
                    assertNotNull(r.getHeader("Retry-After"));
                    assertTrue(System.currentTimeMillis() - start < 10_000, "answered at the timeout, not when validation finished");
                    ctx.completeNow();
                })));
    }
}
