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
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
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
                            .put("allowInsecureSources", true).put("maxCompressedMb", 1));
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
    void uploadOverTheCompressedLimitIsRefusedBeforeReading(Vertx vertx, VertxTestContext ctx) throws java.io.IOException {
        java.nio.file.Path big = java.nio.file.Files.createTempFile("too-big", ".tgz");
        java.nio.file.Files.write(big, new byte[2 * 1024 * 1024]);
        AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/upload")).timeout(10_000)
                .sendMultipartForm(io.vertx.ext.web.multipart.MultipartForm.create()
                        .binaryFileUpload("file", "too-big.tgz", big.toString(), "application/gzip"))
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 413, "package-too-large"));
                    return app.bootstrap().services().pool().query(
                            "SELECT count(*) FROM fhir_validator_schema.fhir_implementation_guides WHERE source = 'UPLOAD'").execute();
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(0L, rows.iterator().next().getLong(0), "nothing stored");
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

    /**
     * CHK038: an IG declared for another FHIR version is refused before anything is committed.
     */
    @Test
    void includeOfAnIgForAnotherFhirVersionIs400(VertxTestContext ctx) {
        var services = app.bootstrap().services();
        // Source URL: uploadOverTheCompressedLimitIsRefusedBeforeReading counts UPLOAD rows in the shared schema.
        services.igPackageService().registerIg(TestPackages.npmPackage("example.r4only", "1.0.0", Map.of(), List.of()), false, "URL")
                .compose(p -> AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R5/include-ig")).timeout(10_000)
                        .sendJsonObject(new JsonObject().put("igPackageId", "example.r4only").put("igPackageVersion", "1.0.0")))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(400, r.statusCode(), r.bodyAsString());
                        JsonObject problem = r.bodyAsJsonObject();
                        assertTrue(problem.getString("type").endsWith("/invalid-request"), problem.encode());
                        assertTrue(problem.getString("detail").contains("R4") && problem.getString("detail").contains("R5"),
                                problem.encode());
                    });
                    return nzi.fhir.validator.core.service.ConfigRevisionRepository.current(services.pool());
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(List.of(), rows.get(nzi.fhir.validator.core.enums.SupportedFhirVersion.R5).included(),
                            "nothing was committed for R5");
                    ctx.completeNow();
                })));
    }

    private Future<io.vertx.ext.web.client.HttpResponse<Buffer>> include(String name) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig")).timeout(10_000)
                .sendJsonObject(new JsonObject().put("igPackageId", name).put("igPackageVersion", "1.0.0"));
    }

    private void restoreLocalApply() {
        var services = app.bootstrap().services();
        services.igPackageService().setApplyLocally(services.configSync()::syncNow);
        services.igPackageService().setLocalApplyWaitMs(10_000);
    }

    /**
     * Constitution IV regression: a committed include whose local apply fails reports curated text, never the cause.
     */
    @Test
    void includeWarningsContainNoInternalMessages(VertxTestContext ctx) {
        var igs = app.bootstrap().services().igPackageService();
        igs.registerIg(TestPackages.npmPackage("example.warn", "1.0.0", Map.of(), List.of()))
                .compose(p -> {
                    igs.setApplyLocally(v -> Future.failedFuture(new IllegalStateException("secret-internal")));
                    return include("example.warn");
                })
                .onComplete(ar -> restoreLocalApply())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    String warnings = r.bodyAsJsonObject().getJsonArray("warnings").encode();
                    assertFalse(warnings.contains("secret-internal") || warnings.contains("Exception"), warnings);
                    assertTrue(warnings.contains("/health"), warnings);
                    ctx.completeNow();
                })));
    }

    /**
     * FR-020: a slow local rebuild does not hold the include answer past the bounded wait.
     */
    @Test
    void includeAnswersWhileTheLocalRebuildIsStillRunning(VertxTestContext ctx) {
        var igs = app.bootstrap().services().igPackageService();
        igs.registerIg(TestPackages.npmPackage("example.slow", "1.0.0", Map.of(), List.of()))
                .compose(p -> {
                    igs.setApplyLocally(v -> io.vertx.core.Promise.<Void>promise().future()); // never completes
                    igs.setLocalApplyWaitMs(300);
                    return include("example.slow");
                })
                .onComplete(ar -> restoreLocalApply())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertNotNull(body.getLong("revision"));
                    assertTrue(body.getJsonArray("warnings").encode().contains("still rebuilding"), body.encode());
                    ctx.completeNow();
                })));
    }
}
