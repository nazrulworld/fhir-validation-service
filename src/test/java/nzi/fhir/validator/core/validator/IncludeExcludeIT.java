package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.StubRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Include adds, exclude demotes (spec 004 US2, FR-019 to FR-024, SC-005).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class IncludeExcludeIT extends BaseTestContainer {
    private static final String P1 = "http://example.org/fhir/StructureDefinition/inc-v1";
    private static final String P2 = "http://example.org/fhir/StructureDefinition/inc-v2";
    private static final String OTHER = "http://example.org/fhir/StructureDefinition/inc-other";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private AppHarness app;
    private StubRegistry registry;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry();
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
                .compose(v -> {
                    ServiceRegistry s = services();
                    return TestPackages.storePackage(s, TestPackages.profilePackage("inc.p", "1.0.0",
                                    TestPackages.requiredPatientElements(P1, "birthDate")))
                            .compose(x -> TestPackages.storePackage(s, TestPackages.profilePackage("inc.p", "2.0.0",
                                    TestPackages.requiredPatientElements(P2, "gender"))))
                            .compose(x -> TestPackages.storePackage(s, TestPackages.profilePackage("inc.other", "1.0.0",
                                    TestPackages.requiredPatientElements(OTHER, "gender"))));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (app != null) {
            services().heapBudget().setMaxHeap(() -> Runtime.getRuntime().maxMemory());
        }
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private ServiceRegistry services() {
        return app.bootstrap().services();
    }

    private Future<HttpResponse<Buffer>> include(String id, String version) {
        JsonObject body = new JsonObject().put("igPackageId", id);
        if (version != null) {
            body.put("igPackageVersion", version);
        }
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator").timeout(60_000))
                .sendJsonObject(body);
    }

    private HttpRequest<Buffer> exclude(String ig) {
        return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator").timeout(60_000))
                .addQueryParam("ig", ig);
    }

    private Future<ValidatorSnapshot.Outcome> validate(String ig, String profile) {
        return services().validationService().validate(nzi.fhir.validator.core.security.Caller.system("system"), R4, IgReference.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(profile), Function.identity());
    }

    private static SnapshotKey key(String ig) {
        return SnapshotKey.of(R4, ig);
    }

    @Test
    void includingASecondVersionAddsAContextAndLeavesTheFirstUntouched(VertxTestContext ctx) {
        ValidatorRegistry reg = services().validatorRegistry();
        ValidatorSnapshot[] v1 = new ValidatorSnapshot[1];
        include("inc.p", "1.0.0")
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    v1[0] = reg.current(key("inc.p#1.0.0"));
                    return include("inc.p", "2.0.0");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals(new JsonObject().put("fhirVersion", "R4").put("igPackage", "inc.p#2.0.0"), body.getJsonObject("key"));
                    assertEquals(new JsonArray().add("inc.p#1.0.0").add("inc.p#2.0.0"), body.getJsonArray("included"));
                    assertEquals(new JsonArray().add("inc.p#2.0.0"), body.getJsonArray("resolved"), "only this key's packages");
                    assertTrue(body.getLong("revision") > 2);
                    assertSame(v1[0], reg.current(key("inc.p#1.0.0")), "v1 was not rebuilt (SC-005)");
                    assertEquals(ValidatorRegistry.Status.ACTIVE, reg.status(key("inc.p#1.0.0")));
                    assertEquals(ValidatorRegistry.Kind.PINNED, reg.kind(key("inc.p#2.0.0")));
                    ctx.completeNow();
                })));
    }

    @Test
    void includingAnIncludedKeyAgainChangesNothing(VertxTestContext ctx) {
        long[] revision = new long[1];
        include("inc.p", "1.0.0")
                .compose(r -> {
                    revision[0] = r.bodyAsJsonObject().getLong("revision");
                    return include("inc.p", "1.0.0");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode());
                    assertEquals(revision[0], r.bodyAsJsonObject().getLong("revision"), "no new revision");
                    ctx.completeNow();
                })));
    }

    @Test
    void includeWithoutVersionUsesTheHighestStoredAndNeverDownloads(VertxTestContext ctx) {
        registry.add("inc.p", "9.0.0", TestPackages.npmPackage("inc.p", "9.0.0", java.util.Map.of(), List.of()));
        include("inc.p", null).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            assertEquals(200, r.statusCode(), r.bodyAsString());
            assertEquals("inc.p#2.0.0", r.bodyAsJsonObject().getJsonObject("key").getString("igPackage"));
            assertEquals(0, registry.requests(), "include never contacts a registry (FR-022, FR-031)");
            ctx.completeNow();
        })));
    }

    @Test
    void includedIgsDoNotSeeEachOthersDefinitions(VertxTestContext ctx) {
        include("inc.p", "1.0.0")
                .compose(r -> include("inc.other", "1.0.0"))
                .compose(r -> validate("inc.p#1.0.0", OTHER).transform(Future::succeededFuture))
                .onComplete(ctx.succeeding(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "inc.other's profile is not visible through inc.p (US2-3)");
                    assertEquals("unknown-profile", ((nzi.fhir.validator.web.ProblemException) ar.cause()).type());
                    ctx.completeNow();
                })));
    }

    @Test
    void anIncludeThatCannotFitIs413AndNothingIsCommitted(VertxTestContext ctx) {
        services().heapBudget().setMaxHeap(() -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + 1024 * 1024);
        include("inc.p", "1.0.0")
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 413, "package-too-large"));
                    return ConfigRevisionRepository.current(services().pool());
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(List.of(), rows.get(R4).included());
                    ctx.completeNow();
                })));
    }

    @Test
    void excludeDropsTheContextAndTheIgIsRefusedUntilIncludedAgain(VertxTestContext ctx) {
        ValidatorRegistry reg = services().validatorRegistry();
        include("inc.p", "1.0.0")
                .compose(r -> include("inc.p", "2.0.0"))
                .compose(r -> exclude("inc.p#1.0.0").send())
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        JsonObject body = r.bodyAsJsonObject();
                        assertEquals("inc.p#1.0.0", body.getString("excluded"));
                        assertEquals(new JsonArray().add("inc.p#2.0.0"), body.getJsonArray("included"));
                        assertNull(reg.kind(key("inc.p#1.0.0")), "spec 007 FR-021: dropped, not kept on demand");
                        assertEquals(ValidatorRegistry.Kind.PINNED, reg.kind(key("inc.p#2.0.0")), "other keys unaffected");
                        assertFalse(app.bootstrap().services().healthService().readiness().encode().contains("\"on-demand\""));
                    });
                    return validate("inc.p#1.0.0", P1).transform(Future::succeededFuture);
                })
                .onComplete(ctx.succeeding(ar -> ctx.verify(() -> {
                    nzi.fhir.validator.web.ProblemException p = assertInstanceOf(nzi.fhir.validator.web.ProblemException.class,
                            ar.cause());
                    assertEquals(400, p.status(), "an excluded IG is refused until it is included again");
                    assertTrue(p.detail().contains("not included"), p.detail());
                    ctx.completeNow();
                })));
    }

    @Test
    void excludeErrors(VertxTestContext ctx) {
        Future.all(exclude("inc.p#1.0.0").send(), exclude("inc.p").send(),
                        exclude("inc.p#latest").send(),
                        AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R6/validator").timeout(10_000))
                                .addQueryParam("ig", "inc.p#1.0.0").send())
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    assertProblem(all.resultAt(0), 404, "package-not-found");
                    assertProblem(all.resultAt(1), 400, "invalid-request");
                    assertProblem(all.resultAt(2), 400, "invalid-request");
                    assertEquals(400, ((HttpResponse<?>) all.resultAt(3)).statusCode(), "unsupported version (FR-037)");
                    ctx.completeNow();
                })));
    }

    /**
     * Convergence T067 (US2-6, FR-017): an excluded IG whose dependency is not stored is not kept on demand; a later
     * request for it is refused with 424 like any other on-demand request for that package.
     */
    @Test
    void excludedIgWithAMissingDependencyIsDroppedAndThenRefusedAsNotIncluded(VertxTestContext ctx) {
        ValidatorRegistry reg = services().validatorRegistry();
        TestPackages.storePackage(services(), TestPackages.npmPackage("inc.needsdep", "1.0.0",
                        java.util.Map.of("inc.absent", "1.0.0"), List.of(TestPackages.requiredPatientElements(P1, "birthDate"))))
                .compose(v -> include("inc.needsdep", "1.0.0"))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertFalse(r.bodyAsJsonObject().getJsonArray("warnings").isEmpty(), "include warns about the missing dependency");
                        assertEquals(ValidatorRegistry.Kind.PINNED, reg.kind(key("inc.needsdep#1.0.0")));
                    });
                    return exclude("inc.needsdep#1.0.0").send();
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertNull(reg.kind(key("inc.needsdep#1.0.0")), "dropped, not kept on demand");
                    });
                    return validate("inc.needsdep#1.0.0", P1).transform(Future::succeededFuture);
                })
                .onComplete(ctx.succeeding(ar -> ctx.verify(() -> {
                    // Spec 007 FR-021: nothing is built on demand, so the missing dependency no longer matters here.
                    nzi.fhir.validator.web.ProblemException p = assertInstanceOf(nzi.fhir.validator.web.ProblemException.class, ar.cause());
                    assertEquals(400, p.status());
                    assertTrue(p.detail().contains("not included"), p.detail());
                    ctx.completeNow();
                })));
    }
}
