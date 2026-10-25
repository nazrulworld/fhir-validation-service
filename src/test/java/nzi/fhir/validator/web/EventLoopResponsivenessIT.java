package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.multipart.MultipartForm;
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

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 regression: uploading and including a large IG never stalls the event loop; probes keep answering in time.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class EventLoopResponsivenessIT extends BaseTestContainer {
    private static final String BLOCKED_THREAD_CHECKER = "io.vertx.core.impl.BlockedThreadChecker";

    private Vertx vertx;
    private AppHarness app;
    private LogCapture logs;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        resetSchema();
        vertx = Vertx.vertx(new VertxOptions()
                .setMaxEventLoopExecuteTime(200).setMaxEventLoopExecuteTimeUnit(TimeUnit.MILLISECONDS)
                .setBlockedThreadCheckInterval(100).setBlockedThreadCheckIntervalUnit(TimeUnit.MILLISECONDS));
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD))
                .compose(a -> {
                    app = a;
                    return a.awaitReady(120_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logs != null) {
            logs.close();
        }
        // This test owns its Vertx instance; close it from the JUnit thread (closing it from one of its own
        // event-loop callbacks never completes).
        if (app != null) {
            app.stop().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private Future<Void> probe(String path, AtomicLong max) {
        long start = System.nanoTime();
        return app.client().get(app.port(), "localhost", path).timeout(5_000).send()
                .map(r -> {
                    max.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Math::max);
                    return null;
                });
    }

    @Test
    void renderingALargeOperationOutcomeDoesNotBlockTheEventLoop(VertxTestContext ctx) {
        logs = LogCapture.start();
        app.client().post(app.port(), "localhost", "/R4/validate").timeout(120_000)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/fhir+xml")
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(nzi.fhir.validator.testsupport.TestPackages.patientBundle(1500)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertTrue(r.bodyAsString().startsWith("<OperationOutcome"));
                    assertEquals(java.util.List.of(), logs.from(BLOCKED_THREAD_CHECKER).stream()
                            .filter(e -> e.message().contains("vert.x-eventloop")).map(LogCapture.Captured::message).toList(),
                            "rendering must not block an event loop");
                    ctx.completeNow();
                })));
    }

    @Test
    void probesStayResponsiveWhileAnIgIsUploadedAndIncluded(VertxTestContext ctx) throws URISyntaxException {
        Path usCore = Path.of(getClass().getClassLoader().getResource(Fixtures.US_CORE_IG).toURI());
        AtomicLong maxLiveness = new AtomicLong();
        AtomicLong maxReadiness = new AtomicLong();
        logs = LogCapture.start();
        long timer = vertx.setPeriodic(100, id -> {
            probe("/health/liveness", maxLiveness);
            probe("/health/readiness", maxReadiness);
        });

        AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/upload"))
                .sendMultipartForm(MultipartForm.create().binaryFileUpload("file", "us-core.tgz", usCore.toString(), "application/gzip"))
                .compose(upload -> {
                    ctx.verify(() -> assertEquals(200, upload.statusCode(), upload.bodyAsString()));
                    return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig"))
                            .putHeader("Content-Type", "application/json")
                            .sendJsonObject(new JsonObject().put("igPackageId", "hl7.fhir.us.core").put("igPackageVersion", "7.0.0"));
                })
                .compose(include -> {
                    ctx.verify(() -> assertEquals(200, include.statusCode(), include.bodyAsString()));
                    return vertx.timer(1_000);
                })
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    vertx.cancelTimer(timer);
                    assertTrue(maxLiveness.get() < 1_000, "slowest liveness probe: " + maxLiveness.get() + " ms");
                    assertTrue(maxReadiness.get() < 1_000, "slowest readiness probe: " + maxReadiness.get() + " ms");
                    assertEquals(java.util.List.of(), logs.from(BLOCKED_THREAD_CHECKER).stream()
                            .filter(e -> e.message().contains("vert.x-eventloop")).map(LogCapture.Captured::message).toList(),
                            "no event loop may block");
                    ctx.completeNow();
                })));
    }
}
