package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-014: IG uploads are limited by FV_PACKAGE_MAX_COMPRESSED_MB, not by the general FV_HTTP_MAX_BODY_MB, which
 * still applies to every other request.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class UploadBodyLimitIT extends BaseTestContainer {

    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("http").put("maxBodyMb", 1);
        config.put("packages", new JsonObject().put("maxCompressedMb", 5).put("registryEnabled", false));
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
    void uploadAboveTheBodyLimitReachesTheArchiveCheck(VertxTestContext ctx) throws java.io.IOException {
        byte[] noise = new byte[2 * 1024 * 1024];
        new Random(1).nextBytes(noise);
        Path file = Files.createTempFile("noise", ".tgz");
        Files.write(file, noise);
        AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/upload")).timeout(10_000)
                .sendMultipartForm(MultipartForm.create().binaryFileUpload("file", "noise.tgz", file.toString(), "application/gzip"))
                .compose(upload -> {
                    ctx.verify(() -> {
                        assertNotEquals(413, upload.statusCode(), "a 2 MB upload is within the 5 MB package limit: " + upload.bodyAsString());
                        assertTrue(upload.statusCode() >= 400 && upload.statusCode() < 500, upload.bodyAsString());
                    });
                    return app.client().post(app.port(), "localhost", "/R4/validate").timeout(10_000)
                            .putHeader("Content-Type", "application/json")
                            .sendBuffer(Buffer.buffer(new byte[2 * 1024 * 1024]));
                })
                .onComplete(ctx.succeeding(validate -> ctx.verify(() -> {
                    assertProblem(validate, 413, "payload-too-large");
                    ctx.completeNow();
                })));
    }
}
