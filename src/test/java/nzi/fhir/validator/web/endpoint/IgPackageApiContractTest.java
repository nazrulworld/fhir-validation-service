package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP contract of the {@code /igs/*} operations (FR-020, FR-023: B11, B21).
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class IgPackageApiContractTest extends BaseTestContainer {

    private AppHarness app;
    private HttpServer packageHost;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        Buffer dk = Fixtures.buffer(Fixtures.DK_CORE_IG);
        vertx.createHttpServer()
                .requestHandler(req -> {
                    if (req.path().equals("/dk.tgz")) {
                        req.response().putHeader("Content-Type", "application/gzip").end(dk);
                    } else {
                        req.response().setStatusCode(404).end();
                    }
                })
                .listen(0)
                .compose(server -> {
                    packageHost = server;
                    JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
                    // A local http package host: allowed only through the dev-profile switch.
                    // The package host doubles as the only registry (it answers 404 for unknown packages).
                    config.put("packages", new JsonObject().put("sources", "http://localhost:" + server.actualPort())
                            .put("allowInsecureSources", true));
                    return AppHarness.start(vertx, config);
                })
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> packageHost == null ? Future.succeededFuture() : packageHost.close())
                .onComplete(ar -> ctx.completeNow());
    }

    protected Future<io.vertx.ext.web.client.HttpResponse<Buffer>> registerFromUrl(String url) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register")).timeout(10_000)
                .sendJsonObject(new JsonObject().put("downloadUrl", url));
    }

    @Test
    void downloadReportsActualNameAndVersion(VertxTestContext ctx) {
        registerFromUrl("http://localhost:" + packageHost.actualPort() + "/dk.tgz")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("hl7.fhir.dk.core", r.bodyAsJsonObject().getString("name"));
                    assertEquals("3.4.0", r.bodyAsJsonObject().getString("version"), "B11: version, not name");
                    ctx.completeNow();
                })));
    }

    @Test
    void downloadOfMissingFileIs404(VertxTestContext ctx) {
        registerFromUrl("http://localhost:" + packageHost.actualPort() + "/missing.tgz")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void registerUnknownPackageByNameIs404Promptly(VertxTestContext ctx) {
        long start = System.currentTimeMillis();
        AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register")).timeout(5_000)
                .sendJsonObject(new JsonObject().put("name", "no.such.pkg").put("version", "9.9.9"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    assertTrue(System.currentTimeMillis() - start < 5_000);
                    ctx.completeNow();
                })));
    }

    @Test
    void registerWithoutNameOrUrlIs400(VertxTestContext ctx) {
        AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register")).timeout(5_000).sendJsonObject(new JsonObject())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void dependenciesOfUnknownPackageIs404(VertxTestContext ctx) {
        app.client().get(app.port(), "localhost", "/igs/unknown.pkg/1.0.0/dependencies").timeout(5_000).send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void dependenciesAndFileListingOfStoredPackage(VertxTestContext ctx) {
        app.bootstrap().services().igPackageService().registerIg(Fixtures.bytes(Fixtures.DK_CORE_IG))
                .compose(p -> app.client().get(app.port(), "localhost", "/igs/hl7.fhir.dk.core/3.4.0/dependencies").send())
                .compose(deps -> {
                    ctx.verify(() -> {
                        assertEquals(200, deps.statusCode(), deps.bodyAsString());
                        assertFalse(deps.bodyAsJsonObject().getJsonArray("dependencies").isEmpty());
                    });
                    return app.client().get(app.port(), "localhost", "/igs/hl7.fhir.dk.core/3.4.0/conformance").send();
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertFalse(body.containsKey("conformance"), "B21: no fake conformance verdict");
                    assertFalse(body.getJsonArray("files").isEmpty());
                    ctx.completeNow();
                })));
    }
}
