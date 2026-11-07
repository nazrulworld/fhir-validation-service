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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /igs/register} with a version series such as {@code 1.2.x} (spec 006 US1, US3, US4; FR-006 to FR-010).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class RegisterSeriesIT extends BaseTestContainer {
    private static final String SERIES_MESSAGE = "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)";

    private StubRegistry registry;
    private StubRegistry second;
    private AppHarness app;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry()
                .add("ser.p", "1.2.0", pkg("ser.p", "1.2.0"))
                .add("ser.p", "1.2.3", TestPackages.npmPackage("ser.p", "1.2.3", Map.of("ser.dep", "1.0.0"), List.of()))
                .add("ser.p", "1.3.0", pkg("ser.p", "1.3.0"))
                .add("ser.p", "1.20.1", pkg("ser.p", "1.20.1"))
                .add("ser.p", "2.0.0-ballot", pkg("ser.p", "2.0.0-ballot"))
                .add("ser.pre", "1.2.1-ballot", pkg("ser.pre", "1.2.1-ballot"))
                .add("ser.dep", "1.0.0", pkg("ser.dep", "1.0.0"))
                .add("own.s", "1.2.9", pkg("own.s", "1.2.9"));
        registry.start(vertx).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry.stop())
                .eventually(() -> second == null ? Future.succeededFuture() : second.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private static byte[] pkg(String name, String version) {
        return TestPackages.npmPackage(name, version, Map.of(), List.of());
    }

    private Future<AppHarness> start(Vertx vertx, boolean registryEnabled, String sources) {
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("packages", new JsonObject().put("sources", sources).put("registryEnabled", registryEnabled)
                .put("allowInsecureSources", true));
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    private Future<AppHarness> start(Vertx vertx, boolean registryEnabled) {
        return start(vertx, registryEnabled, registry.url());
    }

    private Future<HttpResponse<Buffer>> register(String name, String version) {
        return register(new JsonObject().put("name", name).put("version", version));
    }

    private Future<HttpResponse<Buffer>> register(JsonObject body) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/igs/register").timeout(30_000)).sendJsonObject(body);
    }

    private Future<HttpResponse<Buffer>> registerAs(String key, String name, String version) {
        return AppHarness.as(app.client().post(app.port(), "localhost", "/igs/register").timeout(30_000), key)
                .sendJsonObject(new JsonObject().put("name", name).put("version", version));
    }

    private Future<Void> storeLocally(String name, String version) {
        return TestPackages.storePackage(app.bootstrap().services(), pkg(name, version));
    }

    private Future<Boolean> stored(String name, String version) {
        return app.bootstrap().services().igPackageService().cacheManager().isPackageExists(name, version);
    }

    private Pool pool() {
        return app.bootstrap().services().pool();
    }

    private static List<Object> warnings(HttpResponse<Buffer> r) {
        return r.bodyAsJsonObject().getJsonArray("warnings").getList();
    }

    // --- US1 ------------------------------------------------------------------------------------------------------

    @Test
    void minorSeriesDownloadsTheHighestMatchWithDependenciesThenReusesIt(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("ser.p", "1.2.x"))
                .compose(first -> {
                    ctx.verify(() -> {
                        assertEquals(200, first.statusCode(), first.bodyAsString());
                        JsonObject body = first.bodyAsJsonObject();
                        assertEquals("1.2.3", body.getString("version"), "never 1.20.1, never 1.3.0");
                        assertEquals("1.2.x", body.getString("requestedVersion"));
                        assertTrue(body.getBoolean("downloaded"));
                        assertEquals(List.of(), warnings(first));
                        assertEquals(2, registry.archiveDownloads(), "ser.p#1.2.3 and its dependency ser.dep#1.0.0");
                    });
                    return stored("ser.dep", "1.0.0");
                })
                .compose(depStored -> {
                    ctx.verify(() -> assertTrue(depStored, "dependencies are stored with the resolved version (FR-008)"));
                    return register("ser.p", "1.2.x");
                })
                .onComplete(ctx.succeeding(again -> ctx.verify(() -> {
                    assertEquals(200, again.statusCode(), again.bodyAsString());
                    assertEquals("1.2.3", again.bodyAsJsonObject().getString("version"));
                    assertFalse(again.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(2, registry.archiveDownloads(), "no archive transfer when the match is stored (FR-007)");
                    ctx.completeNow();
                })));
    }

    @Test
    void majorSeriesAndUpperCaseWildcards(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("ser.p", "1.x.x"))
                .compose(r -> {
                    ctx.verify(() -> assertEquals("1.20.1", r.bodyAsJsonObject().getString("version"), r.bodyAsString()));
                    return register("ser.p", "1.x");
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals("1.20.1", r.bodyAsJsonObject().getString("version"), r.bodyAsString()));
                    return register("ser.p", "1.2.X");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals("1.2.3", r.bodyAsJsonObject().getString("version"), r.bodyAsString());
                        assertEquals("1.2.X", r.bodyAsJsonObject().getString("requestedVersion"));
                    });
                    return register("ser.p", "1.20.x");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("1.20.1", r.bodyAsJsonObject().getString("version"), "1.20.x never yields a 1.2.* version");
                    ctx.completeNow();
                })));
    }

    @Test
    void preReleaseIsUsedOnlyWhenNoReleaseMatches(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("ser.pre", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.1-ballot", r.bodyAsJsonObject().getString("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void includeDependencyFalseSkipsDependencies(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register(new JsonObject().put("name", "ser.p").put("version", "1.2.x").put("includeDependency", false)))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals("1.2.3", r.bodyAsJsonObject().getString("version"));
                    });
                    return stored("ser.dep", "1.0.0");
                })
                .onComplete(ctx.succeeding(depStored -> ctx.verify(() -> {
                    assertFalse(depStored);
                    ctx.completeNow();
                })));
    }

    @Test
    void disabledRegistriesFallBackToTheHighestStoredMatch(Vertx vertx, VertxTestContext ctx) {
        start(vertx, false)
                .compose(a -> storeLocally("ser.p", "1.2.4"))
                .compose(v -> storeLocally("ser.p", "1.20.0"))
                .compose(v -> register("ser.p", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.4", r.bodyAsJsonObject().getString("version"));
                    assertFalse(r.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(List.of("No package registry could be consulted; using the highest stored version in series 1.2.x"),
                            warnings(r));
                    assertEquals(0, registry.requests(), "disabled registries are never contacted");
                    ctx.completeNow();
                })));
    }

    @Test
    void unavailableRegistryFallsBackWithTheSameWarning(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("ser.p", "1.2.4"))
                .compose(v -> {
                    registry.mode(StubRegistry.Mode.UNAVAILABLE);
                    return register("ser.p", "1.2.x");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.4", r.bodyAsJsonObject().getString("version"));
                    assertEquals(List.of("No package registry could be consulted; using the highest stored version in series 1.2.x"),
                            warnings(r));
                    ctx.completeNow();
                })));
    }

    @Test
    void noListedMatchFallsBackToAStoredMatch(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> storeLocally("ser.p", "1.4.2"))
                .compose(v -> register("ser.p", "1.4.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.4.2", r.bodyAsJsonObject().getString("version"));
                    assertEquals(List.of("No package registry lists a version in series 1.4.x; using the highest stored version"),
                            warnings(r));
                    ctx.completeNow();
                })));
    }

    @Test
    void nothingListedOrStoredIs404(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("ser.p", "3.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 404, "package-not-found");
                    assertTrue(problem.getString("detail").contains("ser.p"), problem.encode());
                    assertTrue(problem.getString("detail").contains("3.x"), problem.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void exactVersionsAndLatestAreUnchanged(Vertx vertx, VertxTestContext ctx) {
        start(vertx, true)
                .compose(a -> register("ser.p", "1.2.0"))
                .compose(exact -> {
                    ctx.verify(() -> {
                        assertEquals(200, exact.statusCode(), exact.bodyAsString());
                        assertEquals("1.2.0", exact.bodyAsJsonObject().getString("version"));
                        assertEquals("1.2.0", exact.bodyAsJsonObject().getString("requestedVersion"));
                    });
                    return register(new JsonObject().put("name", "ser.p"));
                })
                .onComplete(ctx.succeeding(latest -> ctx.verify(() -> {
                    assertEquals(200, latest.statusCode(), latest.bodyAsString());
                    assertEquals("1.20.1", latest.bodyAsJsonObject().getString("version"), "the highest release, not the ballot");
                    assertEquals("latest", latest.bodyAsJsonObject().getString("requestedVersion"));
                    ctx.completeNow();
                })));
    }

    @Test
    void anAnsweringRegistryDecidesWhenAnotherFails(Vertx vertx, VertxTestContext ctx) {
        second = new StubRegistry().mode(StubRegistry.Mode.UNAVAILABLE);
        second.start(vertx)
                .compose(s -> start(vertx, true, second.url() + "," + registry.url()))
                .compose(a -> register("ser.p", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.3", r.bodyAsJsonObject().getString("version"));
                    assertEquals(List.of(), warnings(r));
                    ctx.completeNow();
                })));
    }

    @Test
    void theHighestMatchAcrossRegistriesWinsAndIsDownloadedOnce(Vertx vertx, VertxTestContext ctx) {
        registry.add("ser.m", "1.2.3", pkg("ser.m", "1.2.3"));
        second = new StubRegistry()
                .add("ser.m", "1.2.3", pkg("ser.m", "1.2.3"))
                .add("ser.m", "1.2.4", pkg("ser.m", "1.2.4"));
        second.start(vertx)
                .compose(s -> start(vertx, true, registry.url() + "," + second.url()))
                .compose(a -> register("ser.m", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.4", r.bodyAsJsonObject().getString("version"));
                    assertTrue(r.bodyAsJsonObject().getBoolean("downloaded"));
                    assertEquals(1, registry.archiveDownloads() + second.archiveDownloads());
                    ctx.completeNow();
                })));
    }

    // --- US3: another organisation's private versions -------------------------------------------------------------

    @Test
    void aPrivateVersionOfAnotherOrganisationIsNeverTheStoredFallback(Vertx vertx, VertxTestContext ctx) {
        List<String> keys = new ArrayList<>();
        start(vertx, false)
                .compose(a -> Future.all(TestPackages.createOrganisation(pool(), "orga"), TestPackages.createOrganisation(pool(), "orgb")))
                .compose(orgs -> {
                    UUID orgA = orgs.resultAt(0);
                    UUID orgB = orgs.resultAt(1);
                    return TestPackages.createKey(pool(), orgA, "a-admin", "ADMIN")
                            .compose(key -> {
                                keys.add(key);
                                return storeLocally("own.r", "1.2.4");
                            })
                            .compose(v -> storeLocally("own.r", "1.2.9"))
                            .compose(v -> TestPackages.setPackageAccess(pool(), "own.r#1.2.9", orgB, false));
                })
                .compose(v -> registerAs(keys.get(0), "own.r", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("1.2.4", r.bodyAsJsonObject().getString("version"));
                    assertFalse(r.bodyAsString().contains("1.2.9"), r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void aRegistryMatchStoredPrivatelyByAnotherOrganisationIs404(Vertx vertx, VertxTestContext ctx) {
        List<String> keys = new ArrayList<>();
        start(vertx, true)
                .compose(a -> Future.all(TestPackages.createOrganisation(pool(), "orga"), TestPackages.createOrganisation(pool(), "orgb")))
                .compose(orgs -> TestPackages.createKey(pool(), orgs.resultAt(0), "a-admin", "ADMIN")
                        .compose(key -> {
                            keys.add(key);
                            return storeLocally("own.s", "1.2.9");
                        })
                        .compose(v -> TestPackages.setPackageAccess(pool(), "own.s#1.2.9", orgs.resultAt(1), false)))
                .compose(v -> registerAs(keys.get(0), "own.s", "1.2.x"))
                .compose(series -> {
                    ctx.verify(() -> {
                        JsonObject problem = assertProblem(series, 404, "package-not-found");
                        assertFalse(problem.encode().contains("orgb"), problem.encode());
                    });
                    return registerAs(keys.get(0), "own.s", "1.2.9");
                })
                .onComplete(ctx.succeeding(exact -> ctx.verify(() -> {
                    assertProblem(exact, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    // --- US4: malformed series ------------------------------------------------------------------------------------

    @Test
    void malformedSeriesAre400BeforeAnyRegistryCall(Vertx vertx, VertxTestContext ctx) {
        List<String> bad = List.of("x", "x.x.x", "1.x.3", "1.2.x.y", "1.2.3.x");
        start(vertx, true)
                .compose(a -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (String value : bad) {
                        chain = chain.compose(v -> register("ser.p", value)).map(r -> {
                            ctx.verify(() -> {
                                JsonObject problem = assertProblem(r, 400, "invalid-request");
                                assertEquals(SERIES_MESSAGE, problem.getString("detail"), value);
                            });
                            return null;
                        });
                    }
                    return chain;
                })
                .compose(v -> stored("ser.p", "1.2.3"))
                .onComplete(ctx.succeeding(stored -> ctx.verify(() -> {
                    assertEquals(0, registry.requests(), "a malformed series never reaches a registry (SC-005)");
                    assertFalse(stored);
                    ctx.completeNow();
                })));
    }
}
