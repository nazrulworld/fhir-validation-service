package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.service.IgPackageService.IgEntry;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
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
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_C;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 012 US1 (FR-001 to FR-009): including one validator context built from two IG packages.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class MultiIgIncludeIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String A1 = MULTI_A + "#1.0.0";
    private static final String B2 = MULTI_B + "#2.0.0";
    private static final String KEY = A1 + "|" + B2;

    private CoreHarness core;
    private final Caller system = Caller.system("system");
    private Caller orgA;
    private Caller orgB;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
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
                    return store(TestPackages.multiA(), TestPackages.multiB(), TestPackages.multiC(),
                            TestPackages.conflictX("1.0.0"), TestPackages.conflictX("2.0.0"),
                            TestPackages.multiNeedsX1(), TestPackages.multiNeedsX2(),
                            TestPackages.terminologyPackage("5.0.0"), TestPackages.terminologyPackage("6.0.2"),
                            TestPackages.multiNeedsTx1(), TestPackages.multiNeedsTx2());
                })
                .compose(v -> core.services().configSync().syncCatalogNow())
                .onComplete(ctx.succeedingThenComplete());
    }

    private Future<Void> store(byte[]... packages) {
        Future<Void> f = Future.succeededFuture();
        for (byte[] p : packages) {
            f = f.compose(v -> TestPackages.storePackage(core.services(), p));
        }
        return f;
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private IgPackageService service() {
        return core.services().igPackageService();
    }

    private Future<JsonObject> pair(Caller caller, String a, String av, String b, String bv) {
        return service().includePair(caller, R4, new IgEntry(a, av), new IgEntry(b, bv), Optional.empty(), Optional.empty());
    }

    private Future<Long> snapshotRows() {
        return core.services().pool().preparedQuery("SELECT count(*) FROM fhir_validator_schema.fhir_validator_snapshots "
                        + "WHERE fhir_version = 'R4' AND strpos(ig_package, '|') > 0")
                .execute().map(rows -> rows.iterator().next().getLong(0));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(status, p.status(), p.detail());
        assertEquals(type, p.type(), p.detail());
    }

    private static <T> Future<Throwable> failure(Future<T> f) {
        return f.transform(ar -> ar.succeeded() ? Future.failedFuture("expected a failure, got " + ar.result())
                : Future.succeededFuture(ar.cause()));
    }

    @Test
    void includesOneContextWithBothIgs(VertxTestContext ctx) {
        pair(system, MULTI_A, "1.0.0", MULTI_B, "2.0.0")
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(KEY, r.getJsonObject("key").getString("igPackage"));
                        assertEquals(new JsonArray()
                                        .add(new JsonObject().put("igPackageId", MULTI_A).put("igPackageVersion", "1.0.0"))
                                        .add(new JsonObject().put("igPackageId", MULTI_B).put("igPackageVersion", "2.0.0")),
                                r.getJsonArray("igPackages"));
                        assertEquals(new JsonArray().add(B2 + "|" + A1).add(A1), r.getJsonArray("aliases"));
                        assertTrue(r.getJsonArray("resolved").contains(A1));
                        assertTrue(r.getJsonArray("resolved").contains(B2));
                        assertTrue(r.getJsonArray("included").contains(KEY));
                    });
                    // Pinned on this instance (FR-004).
                    return core.services().validatorRegistry().snapshot(SnapshotKey.of(R4, KEY));
                })
                .onComplete(ctx.succeeding(snapshot -> ctx.verify(() -> {
                    assertNotNull(snapshot);
                    ctx.completeNow();
                })));
    }

    @Test
    void latestAndSeriesResolveToTheConcreteVersion(VertxTestContext ctx) {
        pair(system, MULTI_A, "latest", MULTI_B, "2.x")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(KEY, r.getJsonObject("key").getString("igPackage"));
                    ctx.completeNow();
                })));
    }

    @Test
    void reversedPairReportsTheExistingContext(VertxTestContext ctx) {
        pair(system, MULTI_A, "1.0.0", MULTI_B, "2.0.0")
                .compose(r -> pair(system, MULTI_B, "2.0.0", MULTI_A, "1.0.0"))
                .compose(r -> {
                    ctx.verify(() -> assertEquals(KEY, r.getJsonObject("key").getString("igPackage")));
                    return snapshotRows();
                })
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(1L, count);
                    ctx.completeNow();
                })));
    }

    @Test
    void samePackageTwiceAndTooLongKeysAreRefused(VertxTestContext ctx) {
        failure(pair(system, MULTI_A, "1.0.0", MULTI_A, "1.0.0"))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 400, "invalid-request"));
                    return snapshotRows();
                })
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(0L, count);
                    ctx.completeNow();
                })));
    }

    @Test
    void dependencyConflictIsRefusedNamingThePackage(VertxTestContext ctx) {
        failure(pair(system, "test.multi.p", "1.0.0", "test.multi.q", "1.0.0"))
                .compose(err -> {
                    ctx.verify(() -> {
                        assertProblem(err, 400, "dependency-conflict");
                        JsonArray conflicts = ((ProblemException) err).extensions().getJsonArray("conflicts");
                        assertEquals("test.multi.x", conflicts.getJsonObject(0).getString("package"));
                        assertEquals(new JsonObject().put("test.multi.p#1.0.0", "1.0.0").put("test.multi.q#1.0.0", "2.0.0"),
                                conflicts.getJsonObject(0).getJsonObject("versions"));
                    });
                    return snapshotRows();
                })
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(0L, count);
                    ctx.completeNow();
                })));
    }

    @Test
    void baseTerminologyPackageUsesTheHighestVersionWithAWarning(VertxTestContext ctx) {
        pair(system, "test.multi.t1", "1.0.0", "test.multi.t2", "1.0.0")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonArray resolved = r.getJsonArray("resolved");
                    assertTrue(resolved.contains("hl7.terminology.r4#6.0.2"), resolved.encode());
                    assertFalse(resolved.contains("hl7.terminology.r4#5.0.0"), resolved.encode());
                    List<Object> warnings = r.getJsonArray("warnings").getList();
                    assertTrue(warnings.stream().anyMatch(w -> w.toString().startsWith(
                            "Package hl7.terminology.r4 is required in versions 5.0.0 (test.multi.t1#1.0.0) and 6.0.2 "
                                    + "(test.multi.t2#1.0.0); using 6.0.2")), warnings.toString());
                    assertEquals(1, warnings.stream().filter(w -> w.toString().contains("hl7.terminology.r4")).count(),
                            "one warning per exempt package: " + warnings);
                    ctx.completeNow();
                })));
    }

    @Test
    void aFirstIgStartsOnlyOneTwoIgContext(VertxTestContext ctx) {
        pair(system, MULTI_A, "1.0.0", MULTI_B, "2.0.0")
                .compose(r -> failure(pair(system, MULTI_A, "1.0.0", MULTI_C, "3.0.0")))
                .compose(err -> {
                    ctx.verify(() -> {
                        assertProblem(err, 409, "alias-conflict");
                        assertTrue(((ProblemException) err).detail().contains(KEY), "the system may see the other key");
                    });
                    // Another first IG is fine (US3 scenario 4).
                    return pair(system, MULTI_B, "2.0.0", MULTI_C, "3.0.0");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(B2 + "|" + MULTI_C + "#3.0.0", r.getJsonObject("key").getString("igPackage"));
                    ctx.completeNow();
                })));
    }

    @Test
    void anIgThatIsADependencyOfTheOtherIsAccepted(VertxTestContext ctx) {
        // test.multi.p depends on test.multi.x#1.0.0: including x#1.0.0 together with p is no conflict.
        pair(system, "test.multi.x", "1.0.0", "test.multi.p", "1.0.0")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("test.multi.x#1.0.0|test.multi.p#1.0.0", r.getJsonObject("key").getString("igPackage"));
                    ctx.completeNow();
                })));
    }

    @Test
    void anIgThatIsADependencyOfTheOtherInAnotherVersionConflicts(VertxTestContext ctx) {
        failure(pair(system, "test.multi.x", "2.0.0", "test.multi.p", "1.0.0"))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 400, "dependency-conflict");
                    ctx.completeNow();
                })));
    }

    @Test
    void notStoredOrNotUsableMembersAre404(VertxTestContext ctx) {
        UUID owner = orgA.orgId();
        failure(pair(system, MULTI_A, "1.0.0", "not.stored", "1.0.0"))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 404, "package-not-found"));
                    return TestPackages.setPackageAccess(core.services().pool(), B2, owner, false);
                })
                .compose(v -> failure(pair(orgB, MULTI_A, "1.0.0", MULTI_B, "2.0.0")))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void aDependencyTheCallerMayNotUseIs424(VertxTestContext ctx) {
        TestPackages.setPackageAccess(core.services().pool(), "test.multi.x#1.0.0", orgA.orgId(), false)
                .compose(v -> failure(pair(orgB, "test.multi.p", "1.0.0", MULTI_B, "2.0.0")))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 424, "dependency-not-found");
                    assertFalse(((ProblemException) err).detail().contains("test.multi.x"));
                    ctx.completeNow();
                })));
    }

    @Test
    void aConflictInvolvingAVersionTheCallerMayNotUseIs424(VertxTestContext ctx) {
        TestPackages.setPackageAccess(core.services().pool(), "test.multi.x#2.0.0", orgA.orgId(), false)
                .compose(v -> failure(pair(orgB, "test.multi.p", "1.0.0", "test.multi.q", "1.0.0")))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 424, "dependency-not-found");
                    assertFalse(((ProblemException) err).detail().contains("test.multi.x"));
                    ctx.completeNow();
                })));
    }

    @Test
    void privateNeedsOwnershipOfBothPackages(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        TestPackages.setPackageAccess(pool, A1, orgA.orgId(), true)
                .compose(v -> TestPackages.setPackageAccess(pool, B2, orgB.orgId(), true))
                .compose(v -> failure(service().includePair(orgA, R4, new IgEntry(MULTI_A, "1.0.0"),
                        new IgEntry(MULTI_B, "2.0.0"), Optional.of(false), Optional.empty())))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 400, "invalid-request"));
                    return TestPackages.setPackageAccess(pool, B2, orgA.orgId(), true);
                })
                .compose(v -> service().includePair(orgA, R4, new IgEntry(MULTI_A, "1.0.0"),
                        new IgEntry(MULTI_B, "2.0.0"), Optional.of(false), Optional.empty()))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("orga", r.getString("owner"));
                    assertFalse(r.getBoolean("shareable"));
                    ctx.completeNow();
                })));
    }

    @Test
    void heapAdmissionRefusalStoresNothing(VertxTestContext ctx) {
        service().setAdmitBuild((bytes, extra) -> Future.failedFuture(
                new ProblemException(413, "package-too-large", "does not fit")));
        failure(pair(system, MULTI_A, "1.0.0", MULTI_B, "2.0.0"))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 413, "package-too-large"));
                    return snapshotRows();
                })
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(0L, count);
                    ctx.completeNow();
                })));
    }

    @Test
    void aMemberForAnotherFhirVersionIs400(VertxTestContext ctx) {
        byte[] r5 = TestPackages.igPackage("test.multi.r5", "1.0.0", List.of("5.0.0"), Map.of());
        TestPackages.storePackage(core.services(), r5)
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> failure(pair(system, MULTI_A, "1.0.0", "test.multi.r5", "1.0.0")))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void singleIncludeResponseHasTheSameShape(VertxTestContext ctx) {
        service().include(system, R4, MULTI_A, "1.0.0", Optional.empty())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(new JsonArray().add(new JsonObject().put("igPackageId", MULTI_A).put("igPackageVersion", "1.0.0")),
                            r.getJsonArray("igPackages"));
                    assertEquals(new JsonArray(), r.getJsonArray("aliases"));
                    ctx.completeNow();
                })));
    }

    @Test
    void anIndexViolationIsAnsweredAsAConflictNotIgnored(VertxTestContext ctx) {
        // T022: a write path that skipped the service check gets 409, never a silently missing snapshot row.
        Pool pool = core.services().pool();
        String other = A1 + "|" + MULTI_C + "#3.0.0";
        pool.preparedQuery("INSERT INTO fhir_validator_schema.fhir_validator_snapshots (fhir_version, ig_package) VALUES ('R4', $1)")
                .execute(Tuple.of(KEY))
                .compose(v -> failure(pool.withTransaction(tx -> nzi.fhir.validator.core.service.ConfigRevisionRepository
                        .updateIgSet(tx, R4, List.of(KEY, other), other, nzi.fhir.validator.core.service.ConfigRevisionRepository.KIND_IG))))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 409, "alias-conflict"));
                    return failure(pool.withTransaction(tx -> nzi.fhir.validator.core.service.ConfigRevisionRepository
                            .updateIgSet(tx, R4, List.of(KEY, B2 + "|" + A1), B2 + "|" + A1,
                                    nzi.fhir.validator.core.service.ConfigRevisionRepository.KIND_IG)));
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 409, "conflict");
                    ctx.completeNow();
                })));
    }

    @Test
    void theDatabaseRefusesASecondContextWithTheSameFirstIg(VertxTestContext ctx) {
        // Defence in depth (V17): a write that bypasses the service check is refused by the partial unique index.
        Pool pool = core.services().pool();
        String insert = "INSERT INTO fhir_validator_schema.fhir_validator_snapshots (fhir_version, ig_package) VALUES ('R4', $1)";
        pool.preparedQuery(insert).execute(Tuple.of(KEY))
                .compose(v -> failure(pool.preparedQuery(insert).execute(Tuple.of(A1 + "|" + MULTI_C + "#3.0.0"))))
                .compose(err -> {
                    ctx.verify(() -> assertTrue(err.getMessage().contains("fhir_validator_snapshots_first_member_uq"), err.getMessage()));
                    return failure(pool.preparedQuery(insert).execute(Tuple.of(B2 + "|" + A1)));
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertTrue(err.getMessage().contains("fhir_validator_snapshots_pair_uq"), err.getMessage());
                    ctx.completeNow();
                })));
    }
}
