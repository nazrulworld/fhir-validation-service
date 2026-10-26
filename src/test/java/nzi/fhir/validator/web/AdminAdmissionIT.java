package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CHK036 / FR-010: administrative operations have their own admission limit ({@code FV_ADMIN_MAX_PENDING}),
 * independent of validation. Beyond it they are refused at once with 503 {@code overloaded} and {@code Retry-After};
 * validation keeps being admitted, and the slot is released when the response ends.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class AdminAdmissionIT extends BaseTestContainer {
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    private AppHarness app;
    private HttpServer packageHost;
    /** Completed with the package host's request once a download is in progress (and holding the admin slot). */
    private final Promise<HttpServerRequest> downloadStarted = Promise.promise();

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> packageHost == null ? Future.succeededFuture() : packageHost.close())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<AppHarness> start(Vertx vertx) {
        resetSchema();
        // A package host that holds every download open until the test answers it.
        return vertx.createHttpServer().requestHandler(downloadStarted::tryComplete).listen(0).compose(server -> {
            packageHost = server;
            JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
            config.put("packages", new JsonObject().put("sources", "http://localhost:" + server.actualPort())
                    .put("allowInsecureSources", true));
            config.put("admin", new JsonObject().put("maxPending", 1));
            return AppHarness.start(vertx, config);
        }).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    private Future<HttpResponse<Buffer>> registerFromUrl() {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register")).timeout(30_000)
                .sendJsonObject(new JsonObject().put("downloadUrl", "http://localhost:" + packageHost.actualPort() + "/slow.tgz"));
    }

    private Future<HttpResponse<Buffer>> includeUnknownIg() {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig")).timeout(10_000)
                .sendJsonObject(new JsonObject().put("igPackageId", "example.unknown").put("igPackageVersion", "1.0.0"));
    }

    private Future<HttpResponse<Buffer>> validate() {
        return app.client().post(app.port(), "localhost", "/R4/validate").timeout(10_000)
                .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT));
    }

    @Test
    void adminOperationsBeyondTheirLimitAreRefusedWhileValidationIsStillAdmitted(Vertx vertx, VertxTestContext ctx) {
        start(vertx).compose(a -> {
            Future<HttpResponse<Buffer>> slow = registerFromUrl();
            return downloadStarted.future().compose(download -> includeUnknownIg().compose(refused -> {
                ctx.verify(() -> {
                    assertProblem(refused, 503, "overloaded");
                    assertNotNull(refused.getHeader("Retry-After"), "503 overloaded carries Retry-After");
                });
                return validate();
            }).compose(validated -> {
                ctx.verify(() -> assertEquals(200, validated.statusCode(), "validation is admitted while admin work is full: "
                        + validated.bodyAsString()));
                download.response().setStatusCode(404).end();
                return slow;
            })).compose(slowDone -> includeUnknownIg());
        }).onComplete(ctx.succeeding(afterRelease -> ctx.verify(() -> {
            assertProblem(afterRelease, 404, "package-not-found");
            ctx.completeNow();
        })));
    }
}
