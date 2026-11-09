package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A replaced stored package invalidates every context built from it, on every instance (spec 004 FR-025, research R3,
 * R4, R6); a newly stored dependency version re-resolves the contexts that reference it without a version.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class PackageInvalidationIT extends BaseTestContainer {
    private static final long PROPAGATION_MS = 10_000;
    private static final String P = "http://example.org/fhir/StructureDefinition/inval";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private Vertx vertxB;
    private CoreHarness a;
    private CoreHarness b;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        vertxB = Vertx.vertx();
        CoreHarness.start(vertx, CoreHarnessDb.options())
                .compose(h -> {
                    a = h;
                    return CoreHarness.start(vertxB, CoreHarnessDb.options());
                })
                .onComplete(ctx.succeeding(h -> {
                    b = h;
                    ctx.completeNow();
                }));
    }

    /** Indirection so the static connection options of the container can be used in a lambda. */
    private static final class CoreHarnessDb {
        static io.vertx.pgclient.PgConnectOptions options() {
            return connectOptions();
        }
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> stopA = a == null ? Future.succeededFuture() : a.stop();
        Future<Void> stopB = b == null ? Future.succeededFuture() : b.stop();
        Future.join(stopA, stopB).onComplete(ar -> vertxB.close().onComplete(v -> ctx.completeNow()));
    }

    private static Future<ValidatorSnapshot.Outcome> validate(CoreHarness on, String ig) {
        return on.services().validationService().validate(nzi.fhir.validator.core.security.Caller.system("system"), R4, IgReference.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(P), Function.identity());
    }

    private static Future<Boolean> requires(CoreHarness on, String ig, String element) {
        return validate(on, ig).map(o -> TestPackages.hasError(o, element)).recover(err -> Future.succeededFuture(false));
    }

    private static Future<Boolean> pinnedActive(CoreHarness on, String ig) {
        SnapshotKey key = SnapshotKey.of(R4, ig);
        return Future.succeededFuture(on.services().validatorRegistry().kind(key) == ValidatorRegistry.Kind.PINNED
                && on.services().validatorRegistry().status(key) == ValidatorRegistry.Status.ACTIVE);
    }

    @Test
    void replacedContentInvalidatesPinnedContextsOnBothInstances(Vertx vertx, VertxTestContext ctx) {
        SnapshotKey dependent = SnapshotKey.of(R4, "inv.dependent#1.0.0");
        TestPackages.includePackage(a.services(), TestPackages.profilePackage("inv.p", "1.0.0",
                        TestPackages.requiredPatientElements(P, "birthDate")), "inv.p", "1.0.0")
                .compose(v -> TestPackages.includePackage(a.services(), TestPackages.npmPackage("inv.dependent", "1.0.0",
                        Map.of("inv.p", "1.0.0"), List.of()), "inv.dependent", "1.0.0"))
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "pinned on B", () -> pinnedActive(b, "inv.p#1.0.0")))
                .compose(ms -> Await.until(vertx, PROPAGATION_MS, "dependent context on B",
                        () -> requires(b, "inv.dependent#1.0.0", "birthDate")))
                .compose(ms -> {
                    ctx.verify(() -> assertEquals(ValidatorRegistry.Kind.PINNED, b.services().validatorRegistry().kind(dependent)));
                    // Same name#version, new content: the profile now requires gender instead of birthDate.
                    return TestPackages.storePackage(a.services(), TestPackages.profilePackage("inv.p", "1.0.0",
                            TestPackages.requiredPatientElements(P, "gender")));
                })
                .compose(v -> Future.all(
                        Await.until(vertx, PROPAGATION_MS, "A applies the new content", () -> requires(a, "inv.p#1.0.0", "gender")),
                        Await.until(vertx, PROPAGATION_MS, "B applies the new content", () -> requires(b, "inv.p#1.0.0", "gender"))))
                .compose(all -> requires(b, "inv.dependent#1.0.0", "gender"))
                .onComplete(ctx.succeeding(dependentUpdated -> ctx.verify(() -> {
                    assertTrue(dependentUpdated, "the included context containing the replaced package was rebuilt from new content");
                    assertEquals(ValidatorRegistry.Kind.PINNED, b.services().validatorRegistry().kind(SnapshotKey.of(R4, "inv.p#1.0.0")));
                    ctx.completeNow();
                })));
    }

    @Test
    void aNewDependencyVersionReResolvesContextsThatReferenceItWithoutAVersion(Vertx vertx, VertxTestContext ctx) {
        SnapshotKey q = SnapshotKey.of(R4, "inv.q#1.0.0");
        TestPackages.storePackage(a.services(), TestPackages.profilePackage("inv.d", "1.0.0",
                        TestPackages.requiredPatientElements(P, "birthDate").put("version", "1.0.0")))
                .compose(v -> TestPackages.includePackage(a.services(), TestPackages.npmPackage("inv.q", "1.0.0",
                        Map.of("inv.d", "latest"), List.of()), "inv.q", "1.0.0"))
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "B builds inv.q with inv.d#1.0.0",
                        () -> requires(b, "inv.q#1.0.0", "birthDate")))
                .compose(ms -> {
                    ctx.verify(() -> assertEquals(List.of("inv.d#1.0.0", "inv.q#1.0.0"),
                            b.services().validatorRegistry().current(q).resolvedPackages()));
                    return TestPackages.storePackage(a.services(), TestPackages.profilePackage("inv.d", "2.0.0",
                            TestPackages.requiredPatientElements(P, "gender").put("version", "2.0.0")));
                })
                .compose(v -> Future.all(
                        Await.until(vertx, PROPAGATION_MS, "A re-resolves inv.q", () -> requires(a, "inv.q#1.0.0", "gender")),
                        Await.until(vertx, PROPAGATION_MS, "B re-resolves inv.q", () -> requires(b, "inv.q#1.0.0", "gender"))))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    assertEquals(List.of("inv.d#2.0.0", "inv.q#1.0.0"), b.services().validatorRegistry().current(q).resolvedPackages(),
                            "both instances resolve the same dependency set (research R3)");
                    assertEquals(List.of("inv.d#2.0.0", "inv.q#1.0.0"), a.services().validatorRegistry().current(q).resolvedPackages());
                    ctx.completeNow();
                })));
    }

    @Test
    void replacedPackageMakesAPinnedContextStaleUntilRebuilt(Vertx vertx, VertxTestContext ctx) {
        SnapshotKey key = SnapshotKey.of(R4, "inv.slow#1.0.0");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TestPackages.includePackage(a.services(), TestPackages.profilePackage("inv.slow", "1.0.0",
                        TestPackages.requiredPatientElements(P, "birthDate")), "inv.slow", "1.0.0")
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "pinned on B", () -> pinnedActive(b, "inv.slow#1.0.0")))
                .compose(ms -> {
                    b.services().snapshotBuilder().setBuildHook(() -> {
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    return TestPackages.storePackage(a.services(), TestPackages.profilePackage("inv.slow", "1.0.0",
                            TestPackages.requiredPatientElements(P, "gender")));
                })
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "B marks it stale", () -> Future.succeededFuture(
                        b.services().validatorRegistry().status(key) == ValidatorRegistry.Status.STALE)))
                .compose(ms -> {
                    ctx.verify(() -> {
                        String readiness = b.services().healthService().readiness().encode();
                        assertTrue(readiness.contains("package inv.slow#1.0.0 was replaced"), readiness);
                    });
                    return validate(b, "inv.slow#1.0.0").transform(Future::succeededFuture);
                })
                .compose(ar -> {
                    ctx.verify(() -> {
                        assertTrue(ar.failed(), "outdated results are never served");
                        assertEquals(503, ((nzi.fhir.validator.web.ProblemException) ar.cause()).status());
                    });
                    b.services().snapshotBuilder().setBuildHook(null);
                    release.countDown();
                    return Await.until(vertx, PROPAGATION_MS, "rebuilt with the new content", () -> requires(b, "inv.slow#1.0.0", "gender"));
                })
                .onComplete(ctx.succeedingThenComplete());
    }
}
