package nzi.fhir.validator.core.npm;

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
import nzi.fhir.validator.testsupport.StubRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /igs/register} with "latest" resolves against the package registries (spec 004 US3, FR-027 to FR-031,
 * SC-009).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class RegisterLatestIT extends BaseTestContainer {
    private static final String NO_REGISTRY = "No package registry could be consulted; using the highest stored version";
    private static final String NOT_LISTED = "No package registry lists this package; using the highest stored version";

    private StubRegistry registry;
    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry()
                .add("reg.p", "1.0.0", TestPackages.npmPackage("reg.p", "1.0.0", Map.of(), List.of()))
                .add("reg.p", "2.0.0", TestPackages.npmPackage("reg.p", "2.0.0", Map.of("reg.dep", "1.0.0"), List.of()))
                .add("reg.dep", "1.0.0", TestPackages.npmPackage("reg.dep", "1.0.0", Map.of(), List.of()))
                .add("reg.nodeps", "3.0.0", TestPackages.npmPackage("reg.nodeps", "3.0.0", Map.of("reg.dep", "1.0.0"), List.of()))
                // The registry's tag follows publication order; the version list is authoritative.
                .latestTag("reg.p", "1.0.0");
        registry.start(vertx).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<AppHarness> start(Vertx vertx, boolean registryEnabled) {
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("packages", new JsonObject().put("sources", registry.url()).put("registryEnabled", registryEnabled)
                .put("allowInsecureSources", true));
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    private Future<HttpResponse<Buffer>> register(JsonObject body) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register").timeout(30_000)).sendJsonObject(body);
    }

    private Future<Void> storeLocally(String name, String version) {
        return TestPackages.storePackage(app.bootstrap().services(), TestPackages.npmPackage(name, version, Map.of(), List.of()));
    }

    private Future<Boolean> stored(String name, String version) {
        return app.bootstrap().services().igPackageService().cacheManager().isPackageExists(name, version);
    }

    @Test
    void latestDownloadsTheNewerReleaseThenReusesIt(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("reg.p", "1.0.0"))
                .compose(v -> register(new JsonObject().put("name", "reg.p")))
                .compose(first -> {
                    ctx.verify(() -> {
                        assertEquals(200, first.statusCode(), first.bodyAsString());
                        JsonObject body = first.bodyAsJsonObject();
                        assertEquals("2.0.0", body.getString("version"), "the version list wins over dist-tags.latest");
                        assertTrue(body.getBoolean("downloaded"));
                        assertEquals(List.of(), body.getJsonArray("warnings").getList());
                        assertEquals(2, registry.archiveDownloads(), "reg.p#2.0.0 and its dependency reg.dep#1.0.0");
                    });
                    return stored("reg.dep", "1.0.0");
                })
                .compose(depStored -> {
                    ctx.verify(() -> assertTrue(depStored, "dependencies are stored with a new release (FR-028)"));
                    return register(new JsonObject().put("name", "reg.p").put("version", "latest"));
                })
                .onComplete(ctx.succeeding(second -> ctx.verify(() -> {
                    assertEquals(200, second.statusCode(), second.bodyAsString());
                    assertEquals("2.0.0", second.bodyAsJsonObject().getString("version"));
                    assertFalse(second.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(2, registry.archiveDownloads(), "no archive transfer when the latest is stored (SC-009)");
                    ctx.completeNow();
                })));
    }

    @Test
    void includeDependencyFalseSkipsDependencies(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register(new JsonObject().put("name", "reg.nodeps").put("includeDependency", false)))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals("3.0.0", r.bodyAsJsonObject().getString("version"));
                        assertEquals(1, registry.archiveDownloads());
                    });
                    return stored("reg.dep", "1.0.0");
                })
                .onComplete(ctx.succeeding(depStored -> ctx.verify(() -> {
                    assertFalse(depStored);
                    ctx.completeNow();
                })));
    }

    @Test
    void unreachableRegistriesFallBackToTheHighestStoredVersionOr404(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("reg.p", "1.0.0"))
                .compose(v -> {
                    registry.mode(StubRegistry.Mode.UNAVAILABLE);
                    return register(new JsonObject().put("name", "reg.p"));
                })
                .compose(unavailable -> {
                    ctx.verify(() -> {
                        assertEquals(200, unavailable.statusCode(), unavailable.bodyAsString());
                        assertEquals("1.0.0", unavailable.bodyAsJsonObject().getString("version"));
                        assertFalse(unavailable.bodyAsJsonObject().getBoolean("downloaded"));
                        assertEquals(List.of(NO_REGISTRY), unavailable.bodyAsJsonObject().getJsonArray("warnings").getList());
                    });
                    registry.mode(StubRegistry.Mode.DROP);
                    return register(new JsonObject().put("name", "reg.p"));
                })
                .compose(dropped -> {
                    ctx.verify(() -> assertEquals(List.of(NO_REGISTRY), dropped.bodyAsJsonObject().getJsonArray("warnings").getList()));
                    return register(new JsonObject().put("name", "reg.never.stored"));
                })
                .onComplete(ctx.succeeding(missing -> ctx.verify(() -> {
                    assertProblem(missing, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void disabledRegistriesFallBackWithTheSameWarning(Vertx vertx, VertxTestContext ctx) {
        start(vertx, false)
                .compose(a -> storeLocally("reg.p", "1.0.0"))
                .compose(v -> register(new JsonObject().put("name", "reg.p")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.0.0", r.bodyAsJsonObject().getString("version"));
                    assertEquals(List.of(NO_REGISTRY), r.bodyAsJsonObject().getJsonArray("warnings").getList());
                    assertEquals(0, registry.requests(), "disabled registries are never contacted");
                    ctx.completeNow();
                })));
    }

    @Test
    void aPackageNoRegistryListsFallsBackToTheStoredVersion(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("reg.local", "4.0.0"))
                .compose(v -> register(new JsonObject().put("name", "reg.local")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("4.0.0", r.bodyAsJsonObject().getString("version"));
                    assertEquals(List.of(NOT_LISTED), r.bodyAsJsonObject().getJsonArray("warnings").getList());
                    ctx.completeNow();
                })));
    }

    @Test
    void exactVersionsBehaveAsBeforeAndReportDownloaded(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("reg.p", "1.0.0"))
                .compose(v -> register(new JsonObject().put("name", "reg.p").put("version", "1.0.0")))
                .compose(storedExact -> {
                    ctx.verify(() -> {
                        assertEquals(200, storedExact.statusCode(), storedExact.bodyAsString());
                        assertFalse(storedExact.bodyAsJsonObject().getBoolean("downloaded"));
                        assertEquals(0, registry.archiveDownloads());
                    });
                    return register(new JsonObject().put("name", "reg.dep").put("version", "1.0.0"));
                })
                .onComplete(ctx.succeeding(downloadedExact -> ctx.verify(() -> {
                    assertEquals(200, downloadedExact.statusCode(), downloadedExact.bodyAsString());
                    assertTrue(downloadedExact.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(1, registry.archiveDownloads());
                    ctx.completeNow();
                })));
    }

    /**
     * Convergence T072 (spec edge case "Registry newest is lower than stored"): a locally stored pre-release above the
     * registry's latest release does not hide it; register latest returns the registry's version and says so.
     */
    @Test
    void registryLatestBelowAStoredPreReleaseIsStillReturned(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("reg.p", "3.0.0-ballot"))
                .compose(v -> register(new JsonObject().put("name", "reg.p")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("2.0.0", r.bodyAsJsonObject().getString("version"), "the registry's latest release");
                    assertTrue(r.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(List.of(), r.bodyAsJsonObject().getJsonArray("warnings").getList());
                    ctx.completeNow();
                })));
    }
}
