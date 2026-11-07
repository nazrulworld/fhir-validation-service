package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2/B3 regression: two instances on one database. IG changes made through A, including the profiles they define,
 * take effect on B within 10 s without a restart, via notification and via the poll fallback; a configuration B cannot
 * apply makes that version unavailable on B (never outdated results) until it recovers. Since 2.0.0 included IGs are the
 * only source of profiles (spec 003, FR-011, SC-005).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class ConfigPropagationIT extends BaseTestContainer {
    private static final long PROPAGATION_MS = 10_000;

    private Vertx vertxB;
    private CoreHarness a;
    private CoreHarness b;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        vertxB = Vertx.vertx();
        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    a = h;
                    return CoreHarness.start(vertxB, connectOptions());
                })
                .onComplete(ctx.succeeding(h -> {
                    b = h;
                    ctx.completeNow();
                }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> stopA = a == null ? Future.succeededFuture() : a.stop();
        Future<Void> stopB = b == null ? Future.succeededFuture() : b.stop();
        Future.join(stopA, stopB).onComplete(ar -> vertxB.close().onComplete(v -> ctx.completeNow()));
    }

    /**
     * Whether B applies {@code profileUrl} when validating against IG {@code ig}; an unresolved profile counts as
     * "not yet".
     */
    private Future<Boolean> bEnforces(String ig, String profileUrl, String element) {
        return b.services().validationService()
                .validate(nzi.fhir.validator.core.security.Caller.system("system"), SupportedFhirVersion.R4, IgReference.parse(ig), TestPackages.minimalPatient(), SupportedContentType.JSON,
                        List.of(profileUrl), java.util.function.Function.identity())
                .map(o -> TestPackages.hasError(o, element))
                .recover(err -> err instanceof ProblemException p && ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())
                        ? Future.succeededFuture(false)
                        : Future.failedFuture(err));
    }

    /** Whether B holds {@code ig} as a pinned (pre-built) R4 context: proof the include propagated (spec 004 SC-006). */
    private Future<Boolean> bPinned(String ig) {
        SnapshotKey key = SnapshotKey.of(SupportedFhirVersion.R4, ig);
        ValidatorRegistry registry = b.services().validatorRegistry();
        return Future.succeededFuture(registry.kind(key) == ValidatorRegistry.Kind.PINNED
                && registry.status(key) == ValidatorRegistry.Status.ACTIVE);
    }

    private Future<Void> includeThroughA(String name, String url, String element) {
        return TestPackages.includePackage(a.services(),
                TestPackages.profilePackage(name, "1.0.0", TestPackages.requiredPatientElements(url, element)), name, "1.0.0");
    }

    @Test
    void includedProfileReachesTheOtherInstance(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/from-ig";
        includeThroughA("example.prop", url, "gender")
                .compose(r -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> bPinned("example.prop#1.0.0")))
                .compose(ms -> {
                    ctx.verify(() -> assertEquals(List.of("example.prop#1.0.0"), b.services().validatorRegistry()
                            .current(SnapshotKey.of(SupportedFhirVersion.R4, "example.prop#1.0.0")).resolvedPackages()));
                    return bEnforces("example.prop#1.0.0", url, "gender");
                })
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "B must validate with the profile of the IG included through A");
                    ctx.completeNow();
                })));
    }

    @Test
    void pollFallbackPropagatesWithoutNotifications(Vertx vertx, VertxTestContext ctx) {
        b.services().configSync().setNotificationsEnabled(false);
        String url = "http://example.org/fhir/StructureDefinition/polled";
        includeThroughA("example.polled", url, "birthDate")
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "IG reaches B by polling", () -> bPinned("example.polled#1.0.0")))
                .compose(ms -> bEnforces("example.polled#1.0.0", url, "birthDate"))
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced);
                    ctx.completeNow();
                })));
    }

    @Test
    void unappliableConfigurationMakesVersionUnavailableUntilItRecovers(Vertx vertx, VertxTestContext ctx) {
        var pool = a.services().pool();
        // A committed configuration referencing a package that is not stored: B cannot build it.
        SnapshotKey ghost = SnapshotKey.of(SupportedFhirVersion.R4, "example.ghost#1.0.0");
        pool.withTransaction(tx -> ConfigRevisionRepository.updateIgSet(tx, SupportedFhirVersion.R4,
                        List.of("example.ghost#1.0.0"), "example.ghost#1.0.0", ConfigRevisionRepository.KIND_IG))
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "B marks R4 stale",
                        () -> Future.succeededFuture(b.services().validatorRegistry().status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.STALE)))
                .compose(ms -> {
                    ctx.verify(() -> {
                        ProblemException e = assertThrows(ProblemException.class,
                                () -> b.services().validatorRegistry().current(ghost));
                        assertEquals(503, e.status());
                        assertNotNull(b.services().validatorRegistry().current(SupportedFhirVersion.R4),
                                "the R4 base context keeps serving requests without ig");
                        assertFalse(b.services().validatorRegistry().isReady());
                        // The reason names the missing package, never exception classes or messages (Principle IV).
                        String readiness = b.services().healthService().readiness().encode();
                        assertTrue(readiness.contains("example.ghost#1.0.0"), readiness);
                        assertFalse(readiness.contains("Exception") || readiness.contains("java."), readiness);
                        assertFalse(readiness.contains("profile changes"), "the profile-refresh reason no longer exists");
                        assertNotNull(b.services().validatorRegistry().current(SupportedFhirVersion.R5), "other versions keep serving");
                    });
                    return a.services().igPackageService().registerIg(TestPackages.npmPackage("example.ghost", "1.0.0", Map.of(), List.of()));
                })
                .compose(p -> Await.until(vertx, 30_000, "B recovers once the package is stored",
                        () -> Future.succeededFuture(b.services().validatorRegistry().status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.ACTIVE)))
                .onComplete(ctx.succeeding(ms -> ctx.verify(() -> {
                    assertTrue(b.services().validatorRegistry().isReady());
                    ctx.completeNow();
                })));
    }

    /**
     * FR-008 (001) / FR-011 (003): slow validator rebuilds of two other versions on B must not stop B from serving R4
     * validations with its current IG set. (The 1.x variant propagated a loose profile during the rebuilds; loose
     * profiles no longer exist, and an IG change needs a rebuild of its own.)
     */
    @Test
    void slowRebuildsOfOtherVersionsDoNotStopValidation(Vertx vertx, VertxTestContext ctx) {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger buildsBlocked = new java.util.concurrent.atomic.AtomicInteger();
        SnapshotBuilder builderB = b.services().snapshotBuilder();
        String url = "http://example.org/fhir/StructureDefinition/during-rebuilds";
        ValidatorRegistry registryB = b.services().validatorRegistry();
        includeThroughA("example.before", url, "birthDate")
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> bPinned("example.before#1.0.0")))
                .compose(ms -> a.services().igPackageService().registerIg(TestPackages.npmPackage("example.slowbuild", "1.0.0", Map.of(), List.of())))
                .compose(p -> {
                    builderB.setBuildHook(() -> {
                        buildsBlocked.incrementAndGet();
                        try {
                            release.await(60, TimeUnit.SECONDS); // a long rebuild holding its worker thread
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    // Two rebuilds on B (R4B and R5), each blocked inside the package pool.
                    Map<String, ResolvedContext> slow = Map.of("example.slowbuild#1.0.0",
                            new ResolvedContext(List.of("example.slowbuild#1.0.0"), Map.of()));
                    registryB.applyPinned(SupportedFhirVersion.R4B, 1_000, slow);
                    registryB.applyPinned(SupportedFhirVersion.R5, 1_000, slow);
                    return Await.until(vertx, PROPAGATION_MS, "a rebuild is running on B",
                            () -> Future.succeededFuture(buildsBlocked.get() >= 1));
                })
                .compose(ms -> bEnforces("example.before#1.0.0", url, "birthDate"))
                .onComplete(ar -> {
                    builderB.setBuildHook(null);
                    release.countDown();
                })
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "B keeps validating R4 with its current IG set during other rebuilds");
                    ctx.completeNow();
                })));
    }

    /** Spec 004 US2-6, SC-006: an exclude reaches the other instance within the propagation window, also by polling. */
    @Test
    void excludeReachesTheOtherInstanceAlsoByPolling(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/excluded";
        SnapshotKey key = SnapshotKey.of(SupportedFhirVersion.R4, "example.excl#1.0.0");
        includeThroughA("example.excl", url, "birthDate")
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> bPinned("example.excl#1.0.0")))
                .compose(ms -> {
                    b.services().configSync().setNotificationsEnabled(false);
                    return a.services().igPackageService().exclude(SupportedFhirVersion.R4, "example.excl#1.0.0");
                })
                .compose(r -> Await.until(vertx, PROPAGATION_MS, "exclude reaches B by polling",
                        () -> Future.succeededFuture(b.services().validatorRegistry().kind(key) != ValidatorRegistry.Kind.PINNED)))
                .compose(ms -> bEnforces("example.excl#1.0.0", url, "birthDate"))
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "the excluded IG can still be named on B (built on demand)");
                    assertTrue(b.services().validatorRegistry().isReady());
                    ctx.completeNow();
                })));
    }

    /** Spec 004 FR-006, research R4: a package stored through A can be named in ig on B. */
    @Test
    void packageStoredOnOneInstanceCanBeNamedOnTheOther(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/stored-on-a";
        TestPackages.storePackage(a.services(), TestPackages.profilePackage("example.storedona", "1.0.0",
                        TestPackages.requiredPatientElements(url, "gender")))
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "B can validate against it",
                        () -> bEnforces("example.storedona#1.0.0", url, "gender")
                                .recover(err -> Future.succeededFuture(false))))
                .onComplete(ctx.succeeding(ms -> ctx.verify(() -> {
                    assertEquals(ValidatorRegistry.Kind.ON_DEMAND, b.services().validatorRegistry()
                            .kind(SnapshotKey.of(SupportedFhirVersion.R4, "example.storedona#1.0.0")));
                    ctx.completeNow();
                })));
    }

    /**
     * Spec 005 FR-019, research R8: an included context's access changed through A reaches B within the propagation
     * window, and B swaps it without rebuilding the context (the same snapshot keeps serving).
     */
    @Test
    void snapshotAccessChangeReachesTheOtherInstanceWithoutARebuild(Vertx vertx, VertxTestContext ctx) {
        String ig = "example.access#1.0.0";
        SnapshotKey key = SnapshotKey.of(SupportedFhirVersion.R4, ig);
        java.util.concurrent.atomic.AtomicReference<ValidatorSnapshot> before = new java.util.concurrent.atomic.AtomicReference<>();
        includeThroughA("example.access", "http://example.org/fhir/StructureDefinition/access", "gender")
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> bPinned(ig)))
                .compose(ms -> {
                    before.set(b.services().validatorRegistry().current(key));
                    ctx.verify(() -> assertTrue(b.services().configSync().pinnedAccess(SupportedFhirVersion.R4).get(ig).shareable()));
                    return a.services().pool().withTransaction(tx -> ConfigRevisionRepository.lockForUpdate(tx, SupportedFhirVersion.R4)
                            .compose(row -> ConfigRevisionRepository.changeSnapshotAccess(tx, SupportedFhirVersion.R4, ig,
                                    java.util.Optional.empty(), java.util.Optional.of(false))));
                })
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "access change reaches B", () -> Future.succeededFuture(
                        !b.services().configSync().pinnedAccess(SupportedFhirVersion.R4).get(ig).shareable())))
                .onComplete(ctx.succeeding(ms -> ctx.verify(() -> {
                    assertSame(before.get(), b.services().validatorRegistry().current(key), "no rebuild for an access change");
                    ctx.completeNow();
                })));
    }
}
