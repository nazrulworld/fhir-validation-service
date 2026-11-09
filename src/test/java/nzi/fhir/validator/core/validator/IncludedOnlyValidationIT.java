package nzi.fhir.validator.core.validator;

import io.micrometer.core.instrument.Counter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.StubRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 US5 (FR-021 to FR-025, SC-007): validation is served only by included contexts. A stored, usable version
 * that isn't included is refused with 400 and nothing is built; {@code latest} resolves among included versions; a
 * package the caller can't see is still 404, exactly like one that isn't stored. Replaces the on-demand ITs of spec
 * 004 and spec 005 (OnDemandContextIT, OwnershipOnDemandIT, ContextEvictionIT).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class IncludedOnlyValidationIT extends BaseTestContainer {
    private static final String P = "http://example.org/fhir/StructureDefinition/included-only";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final Caller SYSTEM = Caller.system("system");

    private CoreHarness core;
    private StubRegistry registry;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry();
        registry.start(vertx)
                .compose(r -> CoreHarness.start(vertx, connectOptions(), new JsonObject()
                        .put("packages", new JsonObject().put("sources", registry.url())
                                .put("registryEnabled", true).put("allowInsecureSources", true))))
                .onComplete(ctx.succeeding(h -> {
                    core = h;
                    ctx.completeNow();
                }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<Void> store(String name, String version) {
        return TestPackages.storePackage(core.services(), TestPackages.profilePackage(name, version,
                TestPackages.requiredPatientElements(P, "birthDate")));
    }

    private Future<JsonObject> include(Caller who, String name, String version) {
        return core.services().igPackageService().include(who, R4, name, version, Optional.empty());
    }

    private Future<ValidatorSnapshot.Outcome> validate(Caller who, String ig) {
        return core.services().validationService().validate(who, R4, IgReference.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(P), Function.identity());
    }

    /** Every context build recorded so far, of any kind and outcome. */
    private double builds() {
        return core.services().metrics().registry().find("fv.validator.context.builds").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static ProblemException problem(Throwable err) {
        return assertInstanceOf(ProblemException.class, err);
    }

    /** US5-1: stored, visible, FHIR-compatible, not included → 400, and nothing is built on this instance. */
    @Test
    void aStoredButNotIncludedVersionIs400AndNothingIsBuilt(VertxTestContext ctx) {
        store("io.notincl", "1.0.0").compose(v -> {
            double before = builds();
            return validate(SYSTEM, "io.notincl#1.0.0").transform(ar -> {
                ctx.verify(() -> {
                    ProblemException p = problem(ar.cause());
                    assertEquals(400, p.status());
                    assertEquals("invalid-request", p.type());
                    assertTrue(p.detail().contains("io.notincl#1.0.0"), p.detail());
                    assertTrue(p.detail().contains("include it first"), p.detail());
                    assertEquals(before, builds(), "no build was started (SC-007)");
                    SnapshotKey key = SnapshotKey.of(R4, "io.notincl#1.0.0");
                    assertNull(core.services().validatorRegistry().kind(key), "no context was created");
                    assertFalse(core.services().validatorRegistry().details().encode().contains("io.notincl"),
                            "status lists no context for it");
                });
                return Future.succeededFuture();
            });
        }).onComplete(ctx.succeedingThenComplete());
    }

    /** US5-2 and US5-6: included → 200 from the included context; excluded again → 400, not a build. */
    @Test
    void anIncludedVersionValidatesAndAnExcludedOneIsRefusedAgain(VertxTestContext ctx) {
        store("io.cycle", "1.0.0")
                .compose(v -> include(SYSTEM, "io.cycle", "1.0.0"))
                .compose(v -> validate(SYSTEM, "io.cycle#1.0.0"))
                .compose(o -> {
                    ctx.verify(() -> {
                        assertEquals("io.cycle#1.0.0", o.igPackage());
                        assertTrue(TestPackages.hasError(o, "birthDate"), "validated with the IG's profile");
                    });
                    return core.services().igPackageService().exclude(SYSTEM, R4, "io.cycle#1.0.0");
                })
                .compose(v -> {
                    double before = builds();
                    return validate(SYSTEM, "io.cycle#1.0.0").transform(ar -> {
                        ctx.verify(() -> {
                            ProblemException p = problem(ar.cause());
                            assertEquals(400, p.status());
                            assertTrue(p.detail().contains("is not included for R4"), p.detail());
                            assertEquals(before, builds());
                            assertNull(core.services().validatorRegistry().kind(SnapshotKey.of(R4, "io.cycle#1.0.0")),
                                    "an excluded context is dropped, not kept on demand");
                        });
                        return Future.succeededFuture();
                    });
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    /** US5-3 (FR-023): a versionless or latest reference picks the highest included version, not the highest stored. */
    @Test
    void latestResolvesAmongIncludedVersions(VertxTestContext ctx) {
        Future.all(store("io.latest", "1.0.0"), store("io.latest", "2.0.0"))
                .compose(v -> include(SYSTEM, "io.latest", "1.0.0"))
                .compose(v -> Future.all(validate(SYSTEM, "io.latest"), validate(SYSTEM, "io.latest#latest")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertEquals("io.latest#1.0.0", cf.<ValidatorSnapshot.Outcome>resultAt(0).igPackage());
                    assertEquals("io.latest#1.0.0", cf.<ValidatorSnapshot.Outcome>resultAt(1).igPackage());
                    ctx.completeNow();
                })));
    }

    /** FR-022 with a versionless reference and nothing included: 400 naming the highest stored version. */
    @Test
    void aVersionlessReferenceWithNothingIncludedNamesTheHighestStoredVersion(VertxTestContext ctx) {
        Future.all(store("io.none", "1.0.0"), store("io.none", "1.1.0"))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> validate(SYSTEM, "io.none"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    ProblemException p = problem(err);
                    assertEquals(400, p.status());
                    assertTrue(p.detail().contains("io.none#1.1.0"), p.detail());
                    ctx.completeNow();
                })));
    }

    /**
     * US5-4 (spec 005 FR-012): a package the caller can't use, and one that isn't stored, give the same 404 body; the
     * caller learns nothing about "not included".
     */
    @Test
    void invisibleAndNotStoredPackagesAreTheSame404(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        Future.all(TestPackages.createOrganisation(pool, "orga"), TestPackages.createOrganisation(pool, "orgb"))
                .compose(cf -> {
                    UUID orgA = cf.resultAt(0);
                    UUID orgB = cf.resultAt(1);
                    Caller b = Caller.of(orgB, "orgb", ApiKeyRepository.Role.VALIDATE);
                    return store("io.secret", "1.0.0")
                            .compose(v -> TestPackages.setPackageAccess(pool, "io.secret#1.0.0", orgA, false))
                            .compose(v -> core.services().configSync().syncCatalogNow())
                            .compose(v -> validate(b, "io.secret#1.0.0").transform(hidden ->
                                    validate(b, "io.missing#1.0.0").transform(missing -> {
                                        ctx.verify(() -> {
                                            ProblemException h = problem(hidden.cause());
                                            ProblemException m = problem(missing.cause());
                                            assertEquals(404, h.status());
                                            assertEquals(404, m.status());
                                            assertEquals("package-not-found", h.type());
                                            assertEquals(ProblemException.igNotStored("io.secret#1.0.0").detail(), h.detail());
                                            assertEquals(ProblemException.igNotStored("io.missing#1.0.0").detail(), m.detail());
                                        });
                                        return Future.succeededFuture();
                                    })));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    /** FR-024: the FHIR version check comes before "not included". */
    @Test
    void aPackageForAnotherFhirVersionIs400ForThatReason(VertxTestContext ctx) {
        byte[] r5 = TestPackages.igPackage("io.r5only", "1.0.0", List.of("5.0.0"), Map.of());
        TestPackages.storePackage(core.services(), r5)
                .compose(v -> validate(SYSTEM, "io.r5only#1.0.0"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    ProblemException p = problem(err);
                    assertEquals(400, p.status());
                    assertTrue(p.detail().contains("is for FHIR R5"), p.detail());
                    assertFalse(p.detail().contains("not included"), p.detail());
                    ctx.completeNow();
                })));
    }

    /** US5-5: an included context that isn't ready yet answers 503 not-ready, as before. */
    @Test
    void anIncludedContextStillBuildingIs503(Vertx vertx, VertxTestContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        store("io.slow", "1.0.0").compose(v -> {
                    core.services().snapshotBuilder().setBuildHook(() -> {
                        try {
                            release.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    // The include answers once its local-apply wait ends, with a still-applying warning.
                    return include(SYSTEM, "io.slow", "1.0.0");
                })
                .compose(v -> validate(SYSTEM, "io.slow#1.0.0").transform(ar -> {
                    ctx.verify(() -> {
                        ProblemException p = problem(ar.cause());
                        assertEquals(503, p.status());
                        assertEquals("not-ready", p.type());
                    });
                    core.services().snapshotBuilder().setBuildHook(null);
                    release.countDown();
                    return Future.succeededFuture();
                }))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** The on-demand path never contacts a package registry either: nothing is downloaded for a refused request. */
    @Test
    void aRefusedRequestNeverContactsARegistry(VertxTestContext ctx) {
        validate(SYSTEM, "io.unknown#9.9.9").onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertEquals(404, problem(err).status());
            assertEquals(0, registry.archiveDownloads());
            ctx.completeNow();
        })));
    }
}
