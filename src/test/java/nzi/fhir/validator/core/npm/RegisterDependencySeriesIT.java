package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /igs/register} resolves dependencies declared with a version series or {@code latest} like a top-level
 * registration (spec 006 US5; FR-019, FR-020, FR-022).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class RegisterDependencySeriesIT extends BaseTestContainer {
    private StubRegistry registry;
    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry()
                .add("dep", "1.0.0", pkg("dep", "1.0.0", Map.of()))
                .add("dep", "1.0.3", pkg("dep", "1.0.3", Map.of()))
                .add("dep", "1.1.0", pkg("dep", "1.1.0", Map.of()))
                .add("parent", "1.0.0", pkg("parent", "1.0.0", Map.of("dep", "1.0.x")))
                .add("parentl", "1.0.0", pkg("parentl", "1.0.0", Map.of("dep", "latest")))
                .add("parentc", "1.0.0", pkg("parentc", "1.0.0", Map.of("dep", "current")))
                .add("parentbad", "1.0.0", pkg("parentbad", "1.0.0", Map.of("dep", "1.x.3")))
                .add("parentx", "1.0.0", pkg("parentx", "1.0.0", Map.of("depx", "1.0.x")));
        registry.start(vertx).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private static byte[] pkg(String name, String version, Map<String, String> dependencies) {
        return TestPackages.npmPackage(name, version, dependencies, List.of());
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

    private Future<HttpResponse<Buffer>> register(String name) {
        return register(new JsonObject().put("name", name).put("version", "1.0.0"));
    }

    private Future<Void> storeLocally(String name, String version, Map<String, String> dependencies) {
        return TestPackages.storePackage(app.bootstrap().services(), pkg(name, version, dependencies));
    }

    private Future<Boolean> stored(String name, String version) {
        return app.bootstrap().services().igPackageService().cacheManager().isPackageExists(name, version);
    }

    private Pool pool() {
        return app.bootstrap().services().pool();
    }

    private List<String> archiveRequestsFor(String name) {
        return registry.paths().stream().filter(p -> p.startsWith("/" + name + "/")).toList();
    }

    @Test
    void seriesDependencyDownloadsTheHighestListedMatch(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("parent"))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals(List.of("/dep/1.0.3"), archiveRequestsFor("dep"), "never fetched as the literal dep/1.0.x");
                    });
                    return Future.all(stored("dep", "1.0.3"), stored("dep", "1.1.0"));
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    assertTrue((Boolean) s.resultAt(0), "dep#1.0.3 is stored (FR-019)");
                    assertFalse((Boolean) s.resultAt(1), "dep#1.1.0 is outside the series");
                    ctx.completeNow();
                })));
    }

    @Test
    void seriesDependencyNoRegistryListsFallsBackToAStoredMatch(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("depx", "1.0.2", Map.of()))
                .compose(v -> storeLocally("depx", "1.1.0", Map.of()))
                .compose(v -> register("parentx"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(List.of(), archiveRequestsFor("depx"), "the stored depx#1.0.2 is used, nothing is fetched");
                    assertTrue(registry.paths().contains("/depx"), "the registries are asked first");
                    ctx.completeNow();
                })));
    }

    @Test
    void seriesDependencyWithRegistriesDisabledUsesAStoredMatch(Vertx vertx, VertxTestContext ctx) {
        start(vertx, false)
                .compose(a -> storeLocally("parent", "1.0.0", Map.of("dep", "1.0.x")))
                .compose(v -> storeLocally("dep", "1.0.2", Map.of()))
                .compose(v -> register("parent"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertFalse(r.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(0, registry.requests(), "disabled registries are never contacted");
                    ctx.completeNow();
                })));
    }

    @Test
    void latestAndCurrentDependenciesPreferTheRegistries(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("dep", "1.0.0", Map.of()))
                .compose(v -> register("parentl"))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals(List.of("/dep/1.1.0"), archiveRequestsFor("dep"), "a stored dep#1.0.0 doesn't hide 1.1.0 (FR-020)");
                    });
                    return register("parentc");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals(List.of("/dep/1.1.0"), archiveRequestsFor("dep"), "current resolves to the stored 1.1.0 now");
                    });
                    return stored("dep", "1.1.0");
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    assertTrue(s);
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedSeriesDependencyIsSkippedNotAnError(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("parentbad"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertTrue(registry.paths().stream().noneMatch(p -> p.startsWith("/dep")), registry.paths().toString());
                    ctx.completeNow();
                })));
    }

    @Test
    void includeDependencyFalseSkipsSeriesDependencies(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register(new JsonObject().put("name", "parent").put("version", "1.0.0").put("includeDependency", false)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertTrue(registry.paths().stream().noneMatch(p -> p.startsWith("/dep")), registry.paths().toString());
                    ctx.completeNow();
                })));
    }

    @Test
    void aSeriesDependencyNewlyStoredIsOwnedLikeItsParent(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> TestPackages.createOrganisation(pool(), "orga"))
                .compose(orgA -> TestPackages.createKey(pool(), orgA, "a-admin", "ADMIN"))
                .compose(key -> AppHarness.as(app.client().post(app.port(), "localhost", "/igs/register").timeout(30_000), key)
                        .sendJsonObject(new JsonObject().put("name", "parent").put("version", "1.0.0").put("shareable", false)))
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return app.bootstrap().services().igPackageService().access("dep", "1.0.3");
                })
                .onComplete(ctx.succeeding(access -> ctx.verify(() -> {
                    assertTrue(access.isPresent());
                    assertEquals("orga", access.get().ownerShortName());
                    assertFalse(access.get().shareable());
                    ctx.completeNow();
                })));
    }
}
