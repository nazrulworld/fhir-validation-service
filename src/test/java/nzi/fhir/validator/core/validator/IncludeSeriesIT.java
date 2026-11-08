package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
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
 * {@code POST /{version}/include-ig} with a version series (spec 006 US2, US4; FR-011 to FR-015, SC-004, SC-005).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class IncludeSeriesIT extends BaseTestContainer {
    private static final String SERIES_MESSAGE = "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private AppHarness app;
    private StubRegistry registry;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        // The registry lists a newer matching release that is not stored: include must never see or fetch it.
        registry = new StubRegistry().add("inc.p", "1.2.9", TestPackages.profilePackage("inc.p", "1.2.9"));
        registry.start(vertx).compose(r -> {
                    JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                            POSTGRES_USERNAME, POSTGRES_PASSWORD);
                    config.put("packages", new JsonObject().put("sources", registry.url()).put("registryEnabled", true)
                            .put("allowInsecureSources", true));
                    return AppHarness.start(vertx, config);
                })
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .compose(v -> store("inc.p", "1.2.0"))
                .compose(v -> store("inc.p", "1.2.5"))
                .compose(v -> store("inc.p", "1.20.1"))
                .compose(v -> store("inc.pre", "1.2.1-ballot"))
                .compose(v -> TestPackages.storePackage(services(),
                        TestPackages.igPackage("inc.r5", "1.2.3", List.of("5.0.0"), Map.of())))
                .compose(v -> store("inc.r5", "1.2.1"))
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private ServiceRegistry services() {
        return app.bootstrap().services();
    }

    private Future<Void> store(String name, String version) {
        return TestPackages.storePackage(services(), TestPackages.profilePackage(name, version));
    }

    private Future<HttpResponse<Buffer>> include(String id, String version) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig").timeout(60_000))
                .sendJsonObject(new JsonObject().put("igPackageId", id).put("igPackageVersion", version));
    }

    private static String igPackage(HttpResponse<Buffer> r) {
        return r.bodyAsJsonObject().getJsonObject("key").getString("igPackage");
    }

    private static SnapshotKey key(String ig) {
        return SnapshotKey.of(R4, ig);
    }

    @Test
    void aSeriesIncludesTheHighestStoredMatchWithoutContactingARegistry(VertxTestContext ctx) {
        include("inc.p", "1.2.x")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("inc.p#1.2.5", igPackage(r), "never 1.20.1, never the unstored 1.2.9");
                    assertEquals("1.2.x", r.bodyAsJsonObject().getString("requestedVersion"));
                    assertEquals(ValidatorRegistry.Status.ACTIVE, services().validatorRegistry().status(key("inc.p#1.2.5")));
                    assertEquals(0, registry.requests(), "include never contacts a registry (SC-004)");
                    ctx.completeNow();
                })));
    }

    @Test
    void aPreReleaseIsIncludedWhenItIsTheOnlyMatch(VertxTestContext ctx) {
        include("inc.pre", "1.2.x")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("inc.pre#1.2.1-ballot", igPackage(r));
                    ctx.completeNow();
                })));
    }

    @Test
    void noStoredMatchIs404WithARegisterHint(VertxTestContext ctx) {
        include("inc.p", "2.x.x")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 404, "package-not-found");
                    assertTrue(problem.getString("detail").contains("inc.p#2.x.x"), problem.encode());
                    assertTrue(problem.getString("detail").contains("Register it first"), problem.encode());
                    assertEquals(0, registry.requests());
                    ctx.completeNow();
                })));
    }

    @Test
    void includingTheSeriesAgainAddsTheNewHighestMatchAndKeepsTheFirst(VertxTestContext ctx) {
        ValidatorRegistry reg = services().validatorRegistry();
        ValidatorSnapshot[] first = new ValidatorSnapshot[1];
        include("inc.p", "1.2.x")
                .compose(r -> {
                    ctx.verify(() -> assertEquals("inc.p#1.2.5", igPackage(r), r.bodyAsString()));
                    first[0] = reg.current(key("inc.p#1.2.5"));
                    return store("inc.p", "1.2.6");
                })
                .compose(v -> services().configSync().syncCatalogNow())
                .compose(v -> include("inc.p", "1.2.x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("inc.p#1.2.6", igPackage(r));
                    assertTrue(r.bodyAsJsonObject().getJsonArray("included").contains("inc.p#1.2.5"));
                    assertTrue(r.bodyAsJsonObject().getJsonArray("included").contains("inc.p#1.2.6"));
                    assertSame(first[0], reg.current(key("inc.p#1.2.5")), "the pinned 1.2.5 context is not rebuilt (FR-014)");
                    assertEquals(ValidatorRegistry.Status.ACTIVE, reg.status(key("inc.p#1.2.5")));
                    ctx.completeNow();
                })));
    }

    @Test
    void anIncompatibleHighestMatchFailsWithoutTryingALowerOne(VertxTestContext ctx) {
        include("inc.r5", "1.2.x")
                .compose(series -> {
                    ctx.verify(() -> {
                        JsonObject problem = assertProblem(series, 400, "invalid-request");
                        assertTrue(problem.getString("detail").contains("inc.r5#1.2.3"), problem.encode());
                    });
                    return include("inc.r5", "1.2.3");
                })
                .onComplete(ctx.succeeding(exact -> ctx.verify(() -> {
                    assertProblem(exact, 400, "invalid-request");
                    assertEquals(ValidatorRegistry.Status.ABSENT, services().validatorRegistry().status(key("inc.r5#1.2.1")), "1.2.1 is not chosen (FR-015)");
                    ctx.completeNow();
                })));
    }

    @Test
    void exactVersionsAndLatestAreUnchanged(VertxTestContext ctx) {
        include("inc.p", "1.2.0")
                .compose(exact -> {
                    ctx.verify(() -> {
                        assertEquals(200, exact.statusCode(), exact.bodyAsString());
                        assertEquals("inc.p#1.2.0", igPackage(exact));
                        assertEquals("1.2.0", exact.bodyAsJsonObject().getString("requestedVersion"));
                    });
                    return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/include-ig").timeout(60_000))
                            .sendJsonObject(new JsonObject().put("igPackageId", "inc.p"));
                })
                .onComplete(ctx.succeeding(latest -> ctx.verify(() -> {
                    assertEquals(200, latest.statusCode(), latest.bodyAsString());
                    assertEquals("inc.p#1.20.1", igPackage(latest));
                    assertEquals("latest", latest.bodyAsJsonObject().getString("requestedVersion"));
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedSeriesAre400AndIncludeNothing(VertxTestContext ctx) {
        List<String> bad = List.of("x", "x.x.x", "1.x.3", "1.2.x.y", "1.2.3.x");
        Future<Void> chain = Future.succeededFuture();
        for (String value : bad) {
            chain = chain.compose(v -> include("inc.p", value)).map(r -> {
                ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertEquals(SERIES_MESSAGE, problem.getString("detail"), value);
                });
                return null;
            });
        }
        chain.onComplete(ctx.succeeding(v -> ctx.verify(() -> {
            assertEquals(0, registry.requests());
            assertEquals(ValidatorRegistry.Status.ABSENT, services().validatorRegistry().status(key("inc.p#1.2.5")));
            ctx.completeNow();
        })));
    }

    // --- US5: a dependency declared with a series resolves to the stored match (FR-021) -------------------------------

    @Test
    void aSeriesDependencyResolvesToTheHighestStoredMatch(VertxTestContext ctx) {
        TestPackages.storePackage(services(), TestPackages.npmPackage("incd.parent", "1.0.0", Map.of("incd.dep", "1.0.x"), List.of()))
                .compose(v -> TestPackages.storePackage(services(), TestPackages.npmPackage("incd.dep", "1.0.1", Map.of(), List.of())))
                .compose(v -> TestPackages.storePackage(services(), TestPackages.npmPackage("incd.dep", "1.0.3", Map.of(), List.of())))
                .compose(v -> TestPackages.storePackage(services(), TestPackages.npmPackage("incd.dep", "1.1.0", Map.of(), List.of())))
                .compose(v -> include("incd.parent", "1.0.0"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    List<Object> resolved = r.bodyAsJsonObject().getJsonArray("resolved").getList();
                    assertTrue(resolved.contains("incd.dep#1.0.3"), resolved.toString());
                    assertFalse(resolved.contains("incd.dep#1.1.0"), resolved.toString());
                    assertTrue(r.bodyAsJsonObject().getJsonArray("warnings").stream().noneMatch(w -> w.toString().contains("not stored")),
                            r.bodyAsString());
                    ctx.completeNow();
                })));
    }
}
