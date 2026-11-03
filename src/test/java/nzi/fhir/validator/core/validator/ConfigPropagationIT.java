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

    /** Whether B applies {@code profileUrl}; an unresolved profile (not propagated yet) counts as "not yet". */
    private Future<Boolean> bEnforces(String profileUrl, String element) {
        return b.services().validationService()
                .validate(SupportedFhirVersion.R4, TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(profileUrl))
                .map(o -> TestPackages.hasError(o, element))
                .recover(err -> err instanceof ProblemException p && ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())
                        ? Future.succeededFuture(false)
                        : Future.failedFuture(err));
    }

    private Future<Void> includeThroughA(String name, String url, String element) {
        return TestPackages.includePackage(a.services(),
                TestPackages.profilePackage(name, "1.0.0", TestPackages.requiredPatientElements(url, element)), name, "1.0.0");
    }

    @Test
    void includedProfileReachesTheOtherInstance(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/from-ig";
        includeThroughA("example.prop", url, "gender")
                .compose(r -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> Future.succeededFuture(
                        b.services().validatorRegistry().current(SupportedFhirVersion.R4).resolvedPackages().contains("example.prop#1.0.0"))))
                .compose(ms -> bEnforces(url, "gender"))
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
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "profile reaches B by polling", () -> bEnforces(url, "birthDate")))
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void unappliableConfigurationMakesVersionUnavailableUntilItRecovers(Vertx vertx, VertxTestContext ctx) {
        var pool = a.services().pool();
        // A committed configuration referencing a package that is not stored: B cannot build it.
        pool.withTransaction(tx -> ConfigRevisionRepository.updateIgSet(tx, SupportedFhirVersion.R4,
                        List.of("example.ghost#1.0.0"), List.of("example.ghost#1.0.0"), "example.ghost#1.0.0"))
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "B marks R4 stale",
                        () -> Future.succeededFuture(b.services().validatorRegistry().status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.STALE)))
                .compose(ms -> {
                    ctx.verify(() -> {
                        ProblemException e = assertThrows(ProblemException.class,
                                () -> b.services().validatorRegistry().current(SupportedFhirVersion.R4));
                        assertEquals(503, e.status());
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
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "profile reaches B", () -> bEnforces(url, "birthDate")))
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
                    registryB.apply(SupportedFhirVersion.R4B, 1_000, List.of("example.slowbuild#1.0.0"));
                    registryB.apply(SupportedFhirVersion.R5, 1_000, List.of("example.slowbuild#1.0.0"));
                    return Await.until(vertx, PROPAGATION_MS, "a rebuild is running on B",
                            () -> Future.succeededFuture(buildsBlocked.get() >= 1));
                })
                .compose(ms -> bEnforces(url, "birthDate"))
                .onComplete(ar -> {
                    builderB.setBuildHook(null);
                    release.countDown();
                })
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "B keeps validating R4 with its current IG set during other rebuilds");
                    ctx.completeNow();
                })));
    }
}
