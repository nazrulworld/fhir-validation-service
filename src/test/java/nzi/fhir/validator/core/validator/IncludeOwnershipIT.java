package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 005 US2 scenarios 4-7 (FR-018, research R6): include and exclude record and respect the context's owner.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class IncludeOwnershipIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private CoreHarness core;
    private Caller a;
    private Caller b;
    private final Caller system = Caller.system("system");

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
                    UUID orgA = cf.resultAt(0);
                    a = Caller.of(orgA, "orga", ApiKeyRepository.Role.ADMIN);
                    b = Caller.of(cf.resultAt(1), "orgb", ApiKeyRepository.Role.ADMIN);
                    return store("inc.a", orgA, true).compose(v -> store("inc.c", orgA, true))
                            .compose(v -> store("inc.priv", orgA, false));
                })
                .compose(v -> core.services().configSync().syncCatalogNow())
                .onComplete(ctx.succeedingThenComplete());
    }

    private Future<Void> store(String name, UUID owner, boolean shareable) {
        return TestPackages.storePackage(core.services(), TestPackages.profilePackage(name, "1.0.0"))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), name + "#1.0.0", owner, shareable));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private IgPackageService service() {
        return core.services().igPackageService();
    }

    /** {@code owner|is_shareable} of the snapshot row, or {@code null}. */
    private Future<String> snapshotRow(String key) {
        return core.services().pool().preparedQuery("SELECT owner, is_shareable FROM fhir_validator_schema.fhir_validator_snapshots "
                        + "WHERE fhir_version = 'R4' AND ig_package = $1")
                .execute(Tuple.of(key))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getUUID(0) + "|" + rows.iterator().next().getBoolean(1));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(status, p.status(), p.detail());
        assertEquals(type, p.type());
    }

    private static <T> Future<Throwable> failure(Future<T> f) {
        return f.transform(ar -> ar.succeeded() ? Future.failedFuture("expected a failure") : Future.succeededFuture(ar.cause()));
    }

    @Test
    void includeIsOwnedByTheCallerAndShareableByDefault(VertxTestContext ctx) {
        service().include(a, R4, "inc.a", "1.0.0", Optional.empty())
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals("orga", r.getString("owner"));
                        assertTrue(r.getBoolean("shareable"));
                    });
                    return snapshotRow("inc.a#1.0.0");
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    assertEquals(a.orgId() + "|true", row);
                    ctx.completeNow();
                })));
    }

    @Test
    void onlyThePackageOwnerOrTheSystemMayIncludeItAsPrivate(VertxTestContext ctx) {
        store("inc.b", b.orgId(), true)
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> failure(service().include(a, R4, "inc.b", "1.0.0", Optional.of(false))))
                .compose(err -> {
                    ctx.verify(() -> {
                        assertProblem(err, 400, "invalid-request");
                        assertTrue(((ProblemException) err).detail().contains("Only the owner"));
                    });
                    return service().include(system, R4, "inc.b", "1.0.0", Optional.of(false));
                })
                .compose(r -> {
                    ctx.verify(() -> assertFalse(r.getBoolean("shareable")));
                    return service().include(a, R4, "inc.priv", "1.0.0", Optional.of(false));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("orga", r.getString("owner"), "A owns the package, so A may make its context private");
                    assertFalse(r.getBoolean("shareable"));
                    ctx.completeNow();
                })));
    }

    @Test
    void includeOfAnotherOrganisationsContextReportsItsOwnerOrIsHidden(VertxTestContext ctx) {
        service().include(a, R4, "inc.a", "1.0.0", Optional.empty())
                .compose(shared -> service().include(a, R4, "inc.c", "1.0.0", Optional.of(false)))
                .compose(priv -> snapshotRow("inc.a#1.0.0").compose(rowBefore -> service().include(b, R4, "inc.a", "1.0.0", Optional.empty())
                        .compose(r -> {
                            ctx.verify(() -> {
                                assertEquals("orga", r.getString("owner"), "clarification Q1: the existing owner is kept");
                                assertTrue(r.getBoolean("shareable"));
                                assertEquals(priv.getLong("revision"), r.getLong("revision"), "no new revision");
                            });
                            return snapshotRow("inc.a#1.0.0");
                        })
                        .map(rowAfter -> {
                            ctx.verify(() -> assertEquals(rowBefore, rowAfter, "the row is unchanged"));
                            return rowAfter;
                        })))
                .compose(v -> failure(service().include(b, R4, "inc.c", "1.0.0", Optional.empty())))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void reIncludeWithAnotherSharingWarns(VertxTestContext ctx) {
        service().include(a, R4, "inc.a", "1.0.0", Optional.empty())
                .compose(r -> service().include(a, R4, "inc.a", "1.0.0", Optional.of(false)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertTrue(r.getJsonArray("warnings").contains("Already included; change sharing with PATCH /{version}/include-ig"),
                            r.encode());
                    assertTrue(r.getBoolean("shareable"), "nothing changed");
                    ctx.completeNow();
                })));
    }

    @Test
    void excludeIsForTheOwnerAndTheSystemOnly(VertxTestContext ctx) {
        service().include(a, R4, "inc.a", "1.0.0", Optional.empty())
                .compose(r -> service().include(a, R4, "inc.c", "1.0.0", Optional.empty()))
                .compose(r -> failure(service().exclude(b, R4, "inc.a#1.0.0")))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 404, "package-not-found"));
                    return snapshotRow("inc.a#1.0.0");
                })
                .compose(row -> {
                    ctx.verify(() -> assertNotNull(row, "nothing changed"));
                    return service().exclude(a, R4, "inc.a#1.0.0");
                })
                .compose(r -> service().exclude(system, R4, "inc.c#1.0.0"))
                .compose(r -> Future.all(snapshotRow("inc.a#1.0.0"), snapshotRow("inc.c#1.0.0")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertNull(cf.resultAt(0), "the owner's exclude deletes the row");
                    assertNull(cf.resultAt(1), "the system organisation's exclude deletes the row");
                    ctx.completeNow();
                })));
    }

    @Test
    void anInvisiblePackageCantBeIncluded(VertxTestContext ctx) {
        Future.all(failure(service().include(b, R4, "inc.priv", "1.0.0", Optional.empty())),
                        failure(service().include(b, R4, "no.such.pkg", "1.0.0", Optional.empty())))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    ProblemException hidden = (ProblemException) cf.<Throwable>resultAt(0);
                    ProblemException missing = (ProblemException) cf.<Throwable>resultAt(1);
                    assertProblem(hidden, 404, "package-not-found");
                    assertEquals(missing.detail().replace("no.such.pkg", "inc.priv"), hidden.detail(), "the same answer as not stored");
                    ctx.completeNow();
                })));
    }

    @Test
    void latestIsTheHighestVisibleVersion(VertxTestContext ctx) {
        TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.a", "2.0.0"))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), "inc.a#2.0.0", a.orgId(), false))
                .compose(v -> service().include(b, R4, "inc.a", "latest", Optional.empty()))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("inc.a#1.0.0", r.getJsonObject("key").getString("igPackage"));
                    assertEquals("orgb", r.getString("owner"));
                    ctx.completeNow();
                })));
    }

    /** Spec 006 US3 (SC-007): a series resolves only among versions the caller may use. */
    @Test
    void aSeriesNeverChoosesAnotherOrganisationsPrivateVersion(VertxTestContext ctx) {
        TestPackages.storePackage(core.services(), TestPackages.profilePackage("own.p", "1.2.4"))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.profilePackage("own.p", "1.2.9")))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), "own.p#1.2.9", b.orgId(), false))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> service().include(a, R4, "own.p", "1.2.x", Optional.empty()))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals("own.p#1.2.4", r.getJsonObject("key").getString("igPackage"));
                        assertFalse(r.encode().contains("1.2.9"), r.encode());
                    });
                    return service().include(b, R4, "own.p", "1.2.x", Optional.empty());
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("own.p#1.2.9", r.getJsonObject("key").getString("igPackage"), "the owner sees its private version");
                    ctx.completeNow();
                })));
    }

    /** Spec 006 US3 scenario 2: only a hidden version matches → 404 that names neither it nor its owner. */
    @Test
    void aSeriesMatchingOnlyAHiddenVersionIs404WithoutNamingIt(VertxTestContext ctx) {
        TestPackages.storePackage(core.services(), TestPackages.profilePackage("own.q", "1.2.9"))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), "own.q#1.2.9", b.orgId(), false))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> failure(service().include(a, R4, "own.q", "1.2.x", Optional.empty())))
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "package-not-found");
                    String detail = ((ProblemException) err).detail();
                    assertTrue(detail.contains("own.q#1.2.x"), detail);
                    assertFalse(detail.contains("1.2.9") || detail.contains("orgb"), detail);
                    ctx.completeNow();
                })));
    }

    /** A dependency the caller may not use: 424 without naming it, and nothing is committed (contracts §2). */
    @Test
    void aPrivateDependencyIs424WithoutItsName(VertxTestContext ctx) {
        TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.dep", "1.0.0"))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), "inc.dep#1.0.0", b.orgId(), false))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.igPackage("inc.user", "1.0.0",
                        java.util.List.of("4.0.1"), java.util.Map.of("inc.dep", "1.0.0"))))
                .compose(v -> TestPackages.setPackageAccess(core.services().pool(), "inc.user#1.0.0", a.orgId(), true))
                .compose(v -> failure(service().include(a, R4, "inc.user", "1.0.0", Optional.empty())))
                .compose(err -> {
                    ctx.verify(() -> {
                        assertProblem(err, 424, "dependency-not-found");
                        assertFalse(((ProblemException) err).detail().contains("inc.dep"), ((ProblemException) err).detail());
                    });
                    return snapshotRow("inc.user#1.0.0");
                })
                .compose(row -> {
                    ctx.verify(() -> assertNull(row, "nothing was committed"));
                    return service().include(b, R4, "inc.user", "1.0.0", Optional.empty());
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals("orgb", r.getString("owner"), "the dependency's owner may include it");
                    ctx.completeNow();
                })));
    }

    // --- spec 006 FR-024: a floating dependency never resolves to another organisation's private version ----------

    /** inc.fparent (shareable, A) declares inc.fdep#1.0.x and inc.flat#latest; the highest of each is private to B. */
    private Future<Void> floatingDependencies() {
        Pool pool = core.services().pool();
        return TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.fdep", "1.0.3"))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.fdep", "1.0.5")))
                .compose(v -> TestPackages.setPackageAccess(pool, "inc.fdep#1.0.5", b.orgId(), false))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.flat", "1.0.0")))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.profilePackage("inc.flat", "2.0.0")))
                .compose(v -> TestPackages.setPackageAccess(pool, "inc.flat#2.0.0", b.orgId(), false))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.igPackage("inc.fparent", "1.0.0",
                        java.util.List.of("4.0.1"), java.util.Map.of("inc.fdep", "1.0.x", "inc.flat", "latest"))))
                .compose(v -> TestPackages.setPackageAccess(pool, "inc.fparent#1.0.0", a.orgId(), true))
                .compose(v -> core.services().configSync().syncCatalogNow());
    }

    @Test
    void floatingDependenciesSkipAnotherOrganisationsPrivateVersions(VertxTestContext ctx) {
        floatingDependencies()
                .compose(v -> service().include(a, R4, "inc.fparent", "1.0.0", Optional.empty()))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    java.util.List<Object> resolved = r.getJsonArray("resolved").getList();
                    assertTrue(resolved.contains("inc.fdep#1.0.3"), resolved.toString());
                    assertTrue(resolved.contains("inc.flat#1.0.0"), resolved.toString());
                    assertFalse(r.encode().contains("1.0.5") || r.encode().contains("2.0.0") || r.encode().contains("orgb"), r.encode());
                    SnapshotKey key = SnapshotKey.of(R4, "inc.fparent#1.0.0");
                    assertEquals(ValidatorRegistry.Status.ACTIVE, core.services().validatorRegistry().status(key));
                    java.util.List<String> built = core.services().validatorRegistry().peekResolvedPackages(key).orElseThrow();
                    assertTrue(built.contains("inc.fdep#1.0.3") && built.contains("inc.flat#1.0.0"),
                            "every instance builds what include reported: " + built);
                    ctx.completeNow();
                })));
    }

    @Test
    void theOwnerOfThePrivateVersionsResolvesToThem(VertxTestContext ctx) {
        floatingDependencies()
                .compose(v -> service().include(b, R4, "inc.fparent", "1.0.0", Optional.empty()))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    java.util.List<Object> resolved = r.getJsonArray("resolved").getList();
                    assertTrue(resolved.contains("inc.fdep#1.0.5") && resolved.contains("inc.flat#2.0.0"), resolved.toString());
                    java.util.List<String> built = core.services().validatorRegistry()
                            .peekResolvedPackages(SnapshotKey.of(R4, "inc.fparent#1.0.0")).orElseThrow();
                    assertTrue(built.contains("inc.fdep#1.0.5"), "the context is resolved as its owner, B: " + built);
                    ctx.completeNow();
                })));
    }
}
