package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.core.validator.IgRequest;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 012 US4 (FR-017, FR-018): changing and excluding a two-IG context by its key or reversed pair.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class MultiIgManageIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String A1 = MULTI_A + "#1.0.0";
    private static final String B2 = MULTI_B + "#2.0.0";
    private static final String KEY = A1 + "|" + B2;
    private static final String REVERSED = B2 + "|" + A1;

    private Vertx vertx;
    private CoreHarness core;
    private final Caller system = Caller.system("system");
    private Caller orgA;
    private Caller orgB;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        resetSchema();
        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    core = h;
                    Pool pool = h.services().pool();
                    return Future.all(TestPackages.createOrganisation(pool, "orga"), TestPackages.createOrganisation(pool, "orgb"));
                })
                .compose(cf -> {
                    orgA = Caller.of(cf.resultAt(0), "orga", ApiKeyRepository.Role.ADMIN);
                    orgB = Caller.of(cf.resultAt(1), "orgb", ApiKeyRepository.Role.ADMIN);
                    return TestPackages.storePackage(core.services(), TestPackages.multiA());
                })
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.multiB()))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> TestPackages.includePair(core.services(), R4, MULTI_A, "1.0.0", MULTI_B, "2.0.0"))
                .<Void>mapEmpty()
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private IgPackageService service() {
        return core.services().igPackageService();
    }

    private Future<ValidatorSnapshot.Outcome> validate(String ig) {
        return core.services().validationService().validate(system, R4, IgRequest.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(), Function.identity());
    }

    private static <T> Future<Throwable> failure(Future<T> f) {
        return f.transform(ar -> ar.succeeded() ? Future.failedFuture("expected a failure, got " + ar.result())
                : Future.succeededFuture(ar.cause()));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(status, p.status(), p.detail());
        assertEquals(type, p.type(), p.detail());
    }

    @Test
    void patchByKeyAndReversedPair(VertxTestContext ctx) {
        ValidatorSnapshot before = core.services().validatorRegistry().current(SnapshotKey.of(R4, KEY));
        service().changeSnapshotAccess(system, R4, REVERSED, AccessChange.of(Optional.of(false), Optional.empty()))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(KEY, r.getString("igPackage"));
                        assertFalse(r.getBoolean("shareable"));
                    });
                    return service().changeSnapshotAccess(system, R4, KEY, AccessChange.of(Optional.empty(), Optional.of("orga")));
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals("orga", r.getString("owner")));
                    // Spec 010: a terminology change applies without a rebuild.
                    return service().changeSnapshotAccess(orgA, R4, REVERSED, AccessChange.of(Optional.empty(), Optional.empty(),
                            Optional.of(new TerminologySettings(TerminologyMode.ECOSYSTEM, null))));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("ecosystem", r.getJsonObject("terminology").getString("mode"));
                    assertSame(before, core.services().validatorRegistry().current(SnapshotKey.of(R4, KEY)), "no rebuild");
                    ctx.completeNow();
                })));
    }

    @Test
    void patchCheckOrderIsUnchanged(VertxTestContext ctx) {
        // 404 for a caller that is neither the owner nor the system organisation, then 403 for a transfer by the owner.
        service().changeSnapshotAccess(system, R4, KEY, AccessChange.of(Optional.empty(), Optional.of("orga")))
                .compose(r -> failure(service().changeSnapshotAccess(orgB, R4, REVERSED, AccessChange.of(Optional.of(false), Optional.empty()))))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 404, "package-not-found"));
                    return failure(service().changeSnapshotAccess(orgA, R4, REVERSED, AccessChange.of(Optional.empty(), Optional.of("orgb"))));
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 403, "forbidden");
                    ctx.completeNow();
                })));
    }

    @Test
    void privateNeedsOwnershipOfBothPackages(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        service().changeSnapshotAccess(system, R4, KEY, AccessChange.of(Optional.empty(), Optional.of("orga")))
                .compose(r -> TestPackages.setPackageAccess(pool, A1, orgA.orgId(), true))
                .compose(v -> failure(service().changeSnapshotAccess(orgA, R4, KEY, AccessChange.of(Optional.of(false), Optional.empty()))))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 400, "invalid-request"));
                    return TestPackages.setPackageAccess(pool, B2, orgA.orgId(), true);
                })
                .compose(v -> service().changeSnapshotAccess(orgA, R4, REVERSED, AccessChange.of(Optional.of(false), Optional.empty())))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertFalse(r.getBoolean("shareable"));
                    ctx.completeNow();
                })));
    }

    @Test
    void aSingleValueNeverAddressesATwoIgContext(VertxTestContext ctx) {
        failure(service().changeSnapshotAccess(system, R4, A1, AccessChange.of(Optional.of(false), Optional.empty())))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 404, "package-not-found"));
                    return failure(service().exclude(system, R4, A1));
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void excludeByReversedPairRemovesTheContextAndItsAliases(VertxTestContext ctx) {
        service().exclude(system, R4, REVERSED)
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(KEY, r.getString("excluded"));
                        assertFalse(r.getJsonArray("included").contains(KEY));
                    });
                    return Future.all(failure(validate(KEY)), failure(validate(REVERSED)), failure(validate(A1)));
                })
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < 3; i++) {
                        ProblemException p = assertInstanceOf(ProblemException.class, all.resultAt(i));
                        assertEquals(400, p.status(), p.detail());
                        assertTrue(p.detail().contains("not included"), p.detail());
                    }
                    assertThrows(ProblemException.class, () -> core.services().validatorRegistry().current(SnapshotKey.of(R4, KEY)),
                            "released on this instance");
                    ctx.completeNow();
                })));
    }

    /** FR-018: excluding one context never affects another that contains the same IG. */
    @Test
    void excludingASingleIgContextLeavesTheTwoIgContext(VertxTestContext ctx) {
        service().include(system, R4, MULTI_A, "1.0.0", Optional.empty())
                .compose(r -> service().exclude(system, R4, A1))
                .compose(r -> validate(KEY))
                .compose(o -> {
                    ctx.verify(() -> assertEquals(KEY, o.contextLabel()));
                    return validate(A1);
                })
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals(KEY, o.contextLabel(), "the first-IG alias serves again");
                    ctx.completeNow();
                })));
    }

    /** Spec 009 FR-032 for two-IG contexts, and the unauthenticated inactive listing (members, aliases, privacy). */
    @Test
    void inactiveTwoIgContextCanBeChangedAndIsListedOnlyWhenPublic(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        // What startup's ContextActivation does when R4 is no longer served (spec 009); this harness doesn't run it.
        pool.query("UPDATE fhir_validator_schema.fhir_validator_snapshots SET is_active = false, "
                        + "inactive_reason = 'fhir-version-disabled' WHERE fhir_version = 'R4'").execute()
                .compose(v -> {
                    core.services().healthService().setInactiveCount(1);
                    return service().changeSnapshotAccess(system, R4, REVERSED, AccessChange.of(Optional.of(true), Optional.empty()));
                })
                .compose(r -> core.services().healthService().inactiveContexts())
                .compose(listed -> {
                    ctx.verify(() -> {
                        assertEquals(1L, listed.getLong("count"));
                        JsonObject item = listed.getJsonArray("items").getJsonObject(0);
                        assertEquals(KEY, item.getString("igPackage"));
                        assertEquals(new JsonArray().add(A1).add(B2), item.getJsonArray("igPackages"));
                        assertEquals(new JsonArray().add(REVERSED).add(A1), item.getJsonArray("aliases"));
                    });
                    // The second IG becomes private: the context is counted, never named.
                    return TestPackages.setPackageAccess(pool, B2, orgB.orgId(), false);
                })
                .compose(v -> core.services().healthService().inactiveContexts())
                .onComplete(ctx.succeeding(listed -> ctx.verify(() -> {
                    assertEquals(1L, listed.getLong("count"));
                    assertEquals(1L, listed.getLong("private"));
                    assertEquals(0, listed.getJsonArray("items").size(), listed.encode());
                    ctx.completeNow();
                })));
    }
}
