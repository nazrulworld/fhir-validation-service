package nzi.fhir.validator.core.validator;

import io.micrometer.core.instrument.Counter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.StubRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On-demand validator contexts against real PostgreSQL (spec 004 US1-3, US1-4, FR-010 to FR-014, FR-017, SC-003,
 * research R5): one build for many concurrent first requests, the bounded build wait, build admission, bounded
 * waiting, curated failures, and no registry traffic.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class OnDemandContextIT extends BaseTestContainer {
    private static final String P = "http://example.org/fhir/StructureDefinition/on-demand";

    private CoreHarness core;
    private StubRegistry registry;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry();
        registry.start(vertx).compose(r -> start(vertx, 64)).onComplete(ctx.succeeding(h -> {
            core = h;
            ctx.completeNow();
        }));
    }

    private Future<CoreHarness> start(Vertx vertx, int validationMaxPending) {
        return CoreHarness.start(vertx, connectOptions(), new JsonObject()
                .put("validation", new JsonObject().put("workers", 2).put("maxPending", validationMaxPending))
                .put("admin", new JsonObject().put("maxPending", 1))
                .put("ig", new JsonObject().put("maxOnDemand", 4).put("buildWaitMs", 5_000))
                .put("packages", new JsonObject().put("workers", 3).put("sources", registry.url())
                        .put("registryEnabled", true).put("allowInsecureSources", true)));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<ValidatorSnapshot.Outcome> validate(CoreHarness on, String ig) {
        return on.services().validationService().validate(SupportedFhirVersion.R4, IgReference.parse(ig),
                TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(P), Function.identity());
    }

    private double builds(String outcome) {
        Counter c = core.services().metrics().registry().find("fv.validator.context.builds")
                .tags("kind", "on-demand", "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private Future<Void> store(String name) {
        return TestPackages.storePackage(core.services(), TestPackages.profilePackage(name, "1.0.0",
                TestPackages.requiredPatientElements(P, "birthDate")));
    }

    @Test
    void concurrentFirstRequestsShareOneBuild(Vertx vertx, VertxTestContext ctx) {
        store("od.shared").compose(v -> {
            List<Future<ValidatorSnapshot.Outcome>> all = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                all.add(validate(core, "od.shared#1.0.0"));
            }
            return Future.all(all).map(cf -> all);
        }).onComplete(ctx.succeeding(all -> ctx.verify(() -> {
            assertEquals(1.0, builds("success"), "exactly one build for 50 concurrent first requests (SC-003)");
            assertTrue(all.stream().allMatch(f -> TestPackages.hasError(f.result(), "birthDate")));
            assertTrue(all.stream().allMatch(f -> "od.shared#1.0.0".equals(f.result().igPackage())));
            assertEquals(ValidatorRegistry.Kind.ON_DEMAND, core.services().validatorRegistry()
                    .kind(SnapshotKey.of(SupportedFhirVersion.R4, "od.shared#1.0.0")));
            assertEquals(0, registry.requests(), "on-demand builds read only stored packages (FR-010)");
            ctx.completeNow();
        })));
    }

    @Test
    void aBuildLongerThanTheWaitIs503AndARetrySucceeds(Vertx vertx, VertxTestContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        store("od.slow").compose(v -> {
                    core.services().snapshotBuilder().setBuildHook(() -> await(release));
                    return validate(core, "od.slow#1.0.0");
                })
                .<ValidatorSnapshot.Outcome>transform(ar -> {
                    ctx.verify(() -> {
                        ProblemException p = assertInstanceOf(ProblemException.class, ar.cause());
                        assertEquals(503, p.status());
                        assertEquals("not-ready", p.type());
                        assertEquals("5", p.headers().get("Retry-After"));
                    });
                    core.services().snapshotBuilder().setBuildHook(null);
                    release.countDown();
                    return Await.until(vertx, 30_000, "build completes", () -> Future.succeededFuture(
                                    core.services().validatorRegistry().status(SnapshotKey.of(SupportedFhirVersion.R4, "od.slow#1.0.0"))
                                            == ValidatorRegistry.Status.ACTIVE))
                            .compose(ms -> validate(core, "od.slow#1.0.0"));
                })
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"));
                    assertEquals(1.0, builds("success"), "the build continued after the first request gave up");
                    ctx.completeNow();
                })));
    }

    @Test
    void buildAdmissionRefusesASecondDistinctBuildButJoiningTakesNoSlot(Vertx vertx, VertxTestContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        Future.all(store("od.first"), store("od.second")).compose(v -> {
                    core.services().snapshotBuilder().setBuildHook(() -> await(release));
                    Future<ValidatorSnapshot.Outcome> first = validate(core, "od.first#1.0.0");
                    Future<ValidatorSnapshot.Outcome> joined = validate(core, "od.first#1.0.0");
                    return Await.until(vertx, 10_000, "first build holds the admin slot",
                                    () -> Future.succeededFuture(core.services().adminAdmission().pending() == 1))
                            .compose(ms -> validate(core, "od.second#1.0.0").transform(Future::succeededFuture))
                            .map(second -> {
                                ctx.verify(() -> {
                                    ProblemException p = assertInstanceOf(ProblemException.class, second.cause());
                                    assertEquals(503, p.status());
                                    assertEquals("overloaded", p.type());
                                    assertFalse(joined.isComplete(), "the joining request waits instead of being refused");
                                });
                                core.services().snapshotBuilder().setBuildHook(null);
                                release.countDown();
                                return joined;
                            });
                })
                .compose(joined -> joined)
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"));
                    assertEquals(0, core.services().adminAdmission().pending(), "the slot is released after the build");
                    ctx.completeNow();
                })));
    }

    @Test
    void waitersAreBoundedByValidationAdmission(Vertx vertx, VertxTestContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        core.stop().compose(v -> start(vertx, 3)).compose(h -> {
                    core = h;
                    return store("od.bounded");
                })
                .compose(v -> {
                    core.services().snapshotBuilder().setBuildHook(() -> await(release));
                    List<Future<ValidatorSnapshot.Outcome>> waiting = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                        waiting.add(validate(core, "od.bounded#1.0.0"));
                    }
                    return validate(core, "od.bounded#1.0.0").transform(fourth -> {
                        ctx.verify(() -> {
                            ProblemException p = assertInstanceOf(ProblemException.class, fourth.cause());
                            assertEquals(503, p.status());
                            assertEquals("overloaded", p.type(), "the 4th waiter is refused at once (Principle VI)");
                        });
                        core.services().snapshotBuilder().setBuildHook(null);
                        release.countDown();
                        return Future.succeededFuture();
                    });
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void missingDependencyIs424AndTheNextRequestRetries(Vertx vertx, VertxTestContext ctx) {
        TestPackages.storePackage(core.services(), TestPackages.npmPackage("od.needsdep", "1.0.0",
                        Map.of("od.dep", "1.0.0"), List.of(TestPackages.requiredPatientElements(P, "birthDate"))))
                .compose(v -> validate(core, "od.needsdep#1.0.0").transform(Future::succeededFuture))
                .compose(first -> {
                    ctx.verify(() -> {
                        ProblemException p = assertInstanceOf(ProblemException.class, first.cause());
                        assertEquals(424, p.status());
                        assertEquals("dependency-not-found", p.type());
                        assertTrue(p.detail().contains("od.dep#1.0.0"), p.detail());
                        assertNull(core.services().validatorRegistry().kind(SnapshotKey.of(SupportedFhirVersion.R4, "od.needsdep#1.0.0")),
                                "a failed on-demand build is not cached");
                    });
                    return TestPackages.storePackage(core.services(), TestPackages.npmPackage("od.dep", "1.0.0", Map.of(), List.of()));
                })
                .compose(v -> Await.until(vertx, 10_000, "the stored dependency is known",
                        () -> Future.succeededFuture(core.services().configSync().catalog().current().contains("od.dep#1.0.0"))))
                .compose(ms -> validate(core, "od.needsdep#1.0.0"))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"));
                    ctx.completeNow();
                })));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Convergence T070 (FR-013): once contexts are built, validation needs neither the database nor the network: with
     * the database pool closed, a pinned ig, a versionless ig and the base context all still validate.
     */
    @Test
    void builtContextsValidateWithoutTheDatabase(Vertx vertx, VertxTestContext ctx) {
        store("od.nodb")
                .compose(v -> core.services().igPackageService().include(SupportedFhirVersion.R4, "od.nodb", "1.0.0"))
                .compose(r -> core.services().pool().close())
                .compose(v -> Future.all(validate(core, "od.nodb#1.0.0"), validate(core, "od.nodb"),
                        core.services().validationService().validate(SupportedFhirVersion.R4, null, TestPackages.minimalPatient(),
                                SupportedContentType.JSON, List.of(), Function.identity())))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(all.resultAt(0), "birthDate"));
                    assertEquals("od.nodb#1.0.0", ((ValidatorSnapshot.Outcome) all.resultAt(1)).igPackage());
                    assertNull(((ValidatorSnapshot.Outcome) all.resultAt(2)).igPackage());
                    ctx.completeNow();
                })));
    }
}
