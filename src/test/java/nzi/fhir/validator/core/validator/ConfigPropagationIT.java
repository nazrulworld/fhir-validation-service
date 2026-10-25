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
 * B2/B3 regression: two instances on one database. Changes made through A take effect on B within 10 s without a
 * restart, via notification and via the poll fallback; a configuration B cannot apply makes that version unavailable
 * on B (never outdated results) until it recovers.
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

    private Future<Boolean> bEnforces(String profileUrl, String element) {
        return b.services().validationService()
                .validate(SupportedFhirVersion.R4, TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(profileUrl))
                .map(o -> TestPackages.hasError(o, element));
    }

    @Test
    void profileAndIgChangesReachTheOtherInstance(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/propagated";
        a.services().profileServices().get(SupportedFhirVersion.R4)
                .registerProfile(TestPackages.requiredPatientElements(url, "birthDate"))
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "profile reaches B", () -> bEnforces(url, "birthDate")))
                .compose(ms -> a.services().igPackageService().registerIg(TestPackages.npmPackage("example.prop", "1.0.0", Map.of(),
                        List.of(TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/from-ig", "gender").put("id", "fromig")))))
                .compose(p -> a.services().igPackageService().include(SupportedFhirVersion.R4, "example.prop", "1.0.0"))
                .compose(r -> Await.until(vertx, PROPAGATION_MS, "IG reaches B", () -> Future.succeededFuture(
                        b.services().validatorRegistry().current(SupportedFhirVersion.R4).resolvedPackages().contains("example.prop#1.0.0"))))
                .compose(ms -> bEnforces("http://example.org/fhir/StructureDefinition/from-ig", "gender"))
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "B must validate with the IG included through A");
                    ctx.completeNow();
                })));
    }

    @Test
    void pollFallbackPropagatesWithoutNotifications(Vertx vertx, VertxTestContext ctx) {
        b.services().configSync().setNotificationsEnabled(false);
        String url = "http://example.org/fhir/StructureDefinition/polled";
        a.services().profileServices().get(SupportedFhirVersion.R4)
                .registerProfile(TestPackages.requiredPatientElements(url, "birthDate"))
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "profile reaches B by polling", () -> bEnforces(url, "birthDate")))
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void unappliableConfigurationMakesVersionUnavailableUntilItRecovers(Vertx vertx, VertxTestContext ctx) {
        var pool = a.services().pool();
        // A committed configuration referencing a package that is not stored: B cannot build it.
        pool.withTransaction(tx -> tx.preparedQuery("UPDATE fhir_validator_schema.fhir_validator_logs "
                                + "SET resolved_ig_packages = '{example.ghost#1.0.0}', included_ig_packages = '{example.ghost#1.0.0}' "
                                + "WHERE fhir_version = 'R4'")
                        .execute()
                        .compose(r -> ConfigRevisionRepository.bump(tx, SupportedFhirVersion.R4, ConfigRevisionRepository.KIND_IG, "example.ghost#1.0.0")))
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
     * FR-002: when B knows of a newer profile change but cannot apply it, B stops serving that version instead of
     * answering from the outdated configuration, and recovers automatically once the refresh succeeds.
     */
    @Test
    void failedProfileRefreshMakesVersionUnavailableUntilItRecovers(Vertx vertx, VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/unrefreshable";
        b.services().configSync().setProfileRefreshOverride(v -> Future.failedFuture(new IllegalStateException("refresh failed")));
        a.services().profileServices().get(SupportedFhirVersion.R4)
                .registerProfile(TestPackages.requiredPatientElements(url, "birthDate"))
                .compose(rev -> Await.until(vertx, PROPAGATION_MS, "B marks R4 stale",
                        () -> Future.succeededFuture(b.services().validatorRegistry().status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.STALE)))
                .compose(ms -> {
                    ctx.verify(() -> {
                        ProblemException e = assertThrows(ProblemException.class,
                                () -> b.services().validatorRegistry().current(SupportedFhirVersion.R4));
                        assertEquals(503, e.status());
                        assertFalse(b.services().validatorRegistry().isReady());
                        assertNotNull(b.services().validatorRegistry().current(SupportedFhirVersion.R5), "other versions keep serving");
                    });
                    b.services().configSync().setProfileRefreshOverride(null);
                    return Await.until(vertx, PROPAGATION_MS, "B recovers once the refresh succeeds",
                            () -> Future.succeededFuture(b.services().validatorRegistry().status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.ACTIVE));
                })
                .compose(ms -> bEnforces(url, "birthDate"))
                .onComplete(ctx.succeeding(enforced -> ctx.verify(() -> {
                    assertTrue(enforced, "after recovery B validates with the new profile");
                    ctx.completeNow();
                })));
    }

    /**
     * FR-001/FR-003: profile change timestamps are transaction start times, so a registration that started earlier
     * can commit after a later one. Both must still take effect on the other instance.
     */
    @Test
    void overlappingProfileRegistrationsBothReachTheOtherInstance(Vertx vertx, VertxTestContext ctx) {
        String early = "http://example.org/fhir/StructureDefinition/started-first";
        String late = "http://example.org/fhir/StructureDefinition/committed-first";
        var pool = a.services().pool();
        pool.getConnection().compose(conn -> conn.begin().compose(tx -> conn
                        // modified_at = now() is this transaction's start time, earlier than the registration below.
                        .preparedQuery("INSERT INTO fhir_validator_schema.fhir_profiles (url, profile_json, fhir_version, modified_at) "
                                + "VALUES ($1, $2, '4.0.1', now())")
                        .execute(io.vertx.sqlclient.Tuple.of(early, TestPackages.requiredPatientElements(early, "birthDate")))
                        .compose(r -> vertx.timer(200))
                        .compose(t -> a.services().profileServices().get(SupportedFhirVersion.R4)
                                .registerProfile(TestPackages.requiredPatientElements(late, "gender")))
                        .compose(rev -> Await.until(vertx, PROPAGATION_MS, "later-started profile reaches B", () -> bEnforces(late, "gender")))
                        .compose(ms -> ConfigRevisionRepository.bump(conn, SupportedFhirVersion.R4, ConfigRevisionRepository.KIND_PROFILE, early))
                        .compose(rev -> tx.commit())
                        .eventually(() -> conn.close())))
                .compose(v -> Await.until(vertx, PROPAGATION_MS, "earlier-started profile reaches B", () -> bEnforces(early, "birthDate")))
                .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * A local apply (as after a profile registration) must not complete while a background sync of the same version
     * is still applying: otherwise the caller returns before its own change took effect on this instance.
     */
    @Test
    void localSyncWaitsForABackgroundSyncInFlight(Vertx vertx, VertxTestContext ctx) {
        java.util.concurrent.atomic.AtomicBoolean backgroundDone = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ConfigSync sync = b.services().configSync();
        sync.setProfileRefreshOverride(v -> calls.incrementAndGet() == 1
                ? vertx.timer(500).map(t -> {
                    backgroundDone.set(true);
                    return 0;
                })
                : Future.succeededFuture(0));
        sync.sync(SupportedFhirVersion.R4);
        sync.syncNow(SupportedFhirVersion.R4)
                .onComplete(ar -> sync.setProfileRefreshOverride(null))
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertTrue(backgroundDone.get(), "syncNow completed while the background sync was still running");
                    assertEquals(2, calls.get(), "both syncs ran, one after the other");
                    ctx.completeNow();
                })));
    }
}
