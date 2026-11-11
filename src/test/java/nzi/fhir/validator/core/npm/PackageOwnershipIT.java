package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.core.service.IgPackageService;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 005 US2 scenarios 1-3 (FR-016, FR-017, research R7): what register and upload store, and who may replace it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class PackageOwnershipIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema";

    private StubRegistry registry;
    private CoreHarness core;
    private Caller a;
    private Caller b;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        registry = new StubRegistry()
                .add("reg.dep", "1.0.0", TestPackages.igPackage("reg.dep", "1.0.0", List.of("4.0.1"), Map.of()))
                .add("reg.main", "1.0.0", TestPackages.igPackage("reg.main", "1.0.0", List.of("4.0.1"), Map.of("reg.dep", "1.0.0")))
                .add("reg.privdep", "1.0.0", TestPackages.igPackage("reg.privdep", "1.0.0", List.of("4.0.1"), Map.of()))
                .add("reg.priv", "1.0.0", TestPackages.igPackage("reg.priv", "1.0.0", List.of("4.0.1"), Map.of("reg.privdep", "1.0.0")));
        registry.start(vertx)
                .compose(r -> CoreHarness.start(vertx, connectOptions(), new JsonObject()
                        .put("packages", new JsonObject().put("sources", registry.url()).put("registryEnabled", true)
                                .put("allowInsecureSources", true))))
                .compose(h -> {
                    core = h;
                    Pool pool = h.services().pool();
                    return Future.all(TestPackages.createOrganisation(pool, "orga"), TestPackages.createOrganisation(pool, "orgb"));
                })
                .onComplete(ctx.succeeding(cf -> {
                    a = Caller.of(cf.resultAt(0), "orga", ApiKeyRepository.Role.ADMIN);
                    b = Caller.of(cf.resultAt(1), "orgb", ApiKeyRepository.Role.ADMIN);
                    ctx.completeNow();
                }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop())
                .eventually(() -> registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private IgPackageService service() {
        return core.services().igPackageService();
    }

    /** {@code owner|is_shareable|sha256-hex} of a stored package, or {@code null}. */
    private Future<String> row(String name, String version) {
        return core.services().pool().preparedQuery("SELECT owner, is_shareable, encode(content_sha256, 'hex') FROM " + S
                        + ".fhir_implementation_guides WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getUUID(0) + "|"
                        + rows.iterator().next().getBoolean(1) + "|" + rows.iterator().next().getString(2));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(status, p.status(), p.detail());
        assertEquals(type, p.type());
    }

    private static byte[] pkg(String name, String version, String marker) {
        return TestPackages.igPackage(name, version, List.of("4.0.1"), Map.of(),
                TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/" + marker, "gender"));
    }

    @Test
    void registerStoresTheCallersOwnershipForDependenciesToo(VertxTestContext ctx) {
        service().register(a, "reg.main", "1.0.0", true, true)
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals("orga", r.access().ownerShortName());
                        assertTrue(r.access().shareable());
                        assertTrue(r.downloaded());
                    });
                    return service().register(a, "reg.priv", "1.0.0", true, false);
                })
                .compose(r -> Future.all(row("reg.main", "1.0.0"), row("reg.dep", "1.0.0"), row("reg.priv", "1.0.0"),
                        row("reg.privdep", "1.0.0")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertTrue(cf.<String>resultAt(0).startsWith(a.orgId() + "|true"));
                    assertTrue(cf.<String>resultAt(1).startsWith(a.orgId() + "|true"), "the dependency too");
                    assertTrue(cf.<String>resultAt(2).startsWith(a.orgId() + "|false"));
                    assertTrue(cf.<String>resultAt(3).startsWith(a.orgId() + "|false"), "the private registration's dependency too");
                    ctx.completeNow();
                })));
    }

    @Test
    void registerOfAStoredVersionKeepsItsOwnerAndHidesPrivateOnes(VertxTestContext ctx) {
        service().registerIg(pkg("reg.b", "1.0.0", "b"), false, "UPLOAD", PackageOwnership.of(b, false))
                .compose(p -> service().register(a, "reg.b", "1.0.0", false, true).transform(ar -> Future.succeededFuture(ar.cause())))
                .compose(err -> {
                    ctx.verify(() -> assertProblem(err, 404, "package-not-found"));
                    return core.services().igPackageService().cacheManager().changeAccess("reg.b", "1.0.0", b,
                            nzi.fhir.validator.core.security.AccessChange.of(java.util.Optional.of(true), java.util.Optional.empty()));
                })
                .compose(access -> service().register(a, "reg.b", "1.0.0", false, false))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals("orgb", r.access().ownerShortName(), "the stored owner is reported, unchanged");
                        assertTrue(r.access().shareable(), "the request's shareable=false changes nothing");
                        assertFalse(r.downloaded());
                    });
                    return row("reg.b", "1.0.0");
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    assertTrue(row.startsWith(b.orgId() + "|true"), row);
                    ctx.completeNow();
                })));
    }

    @Test
    void uploadOfAnotherOrganisationsPackageIsAConflictAndChangesNothing(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        service().registerIg(pkg("up.b", "1.0.0", "original"), false, "UPLOAD", PackageOwnership.of(b, true))
                .compose(p -> Future.all(row("up.b", "1.0.0"), ConfigRevisionRepository.catalogRevision(pool)))
                .compose(before -> service().registerIg(pkg("up.b", "1.0.0", "replacement"), false, "UPLOAD",
                                PackageOwnership.of(a, true))
                        .transform(ar -> Future.succeededFuture(ar.cause()))
                        .compose(err -> {
                            ctx.verify(() -> {
                                assertProblem(err, 409, "conflict");
                                assertTrue(((ProblemException) err).detail().contains("up.b#1.0.0"));
                            });
                            return Future.all(row("up.b", "1.0.0"), ConfigRevisionRepository.catalogRevision(pool));
                        })
                        .map(after -> Map.entry(before, after)))
                .onComplete(ctx.succeeding(e -> ctx.verify(() -> {
                    assertEquals(e.getKey().<String>resultAt(0), e.getValue().<String>resultAt(0), "owner and content unchanged");
                    assertEquals(e.getKey().<Long>resultAt(1), e.getValue().<Long>resultAt(1), "the catalog revision isn't bumped");
                    ctx.completeNow();
                })));
    }

    @Test
    void theOwnerOrTheSystemOrganisationReplacesAndTheOwnerIsKept(VertxTestContext ctx) {
        service().registerIg(pkg("up.keep", "1.0.0", "v1"), false, "UPLOAD", PackageOwnership.of(b, false))
                .compose(p -> row("up.keep", "1.0.0"))
                .compose(first -> service().registerIg(pkg("up.keep", "1.0.0", "v2"), false, "UPLOAD", PackageOwnership.of(b, true))
                        .compose(p -> row("up.keep", "1.0.0"))
                        .compose(second -> {
                            ctx.verify(() -> {
                                assertTrue(second.startsWith(b.orgId() + "|false"), "sharing kept: " + second);
                                assertNotEquals(first, second, "content replaced");
                            });
                            return service().registerIg(pkg("up.keep", "1.0.0", "v3"), false, "UPLOAD",
                                    PackageOwnership.of(Caller.system("system"), true));
                        })
                        .compose(p -> row("up.keep", "1.0.0")))
                .onComplete(ctx.succeeding(third -> ctx.verify(() -> {
                    assertTrue(third.startsWith(b.orgId() + "|false"), "the system organisation replaces, the owner stays: " + third);
                    ctx.completeNow();
                })));
    }

    @Test
    void concurrentFirstUploadsOfTheSameVersionLetExactlyOneWin(VertxTestContext ctx) {
        Future<?> byA = service().registerIg(pkg("up.race", "1.0.0", "a"), false, "UPLOAD", PackageOwnership.of(a, true));
        Future<?> byB = service().registerIg(pkg("up.race", "1.0.0", "b"), false, "UPLOAD", PackageOwnership.of(b, true));
        Future.join(byA, byB).onComplete(ar -> ctx.verify(() -> {
            long won = java.util.stream.Stream.of(byA, byB).filter(Future::succeeded).count();
            assertEquals(1, won, "exactly one first upload wins");
            Throwable lost = byA.failed() ? byA.cause() : byB.cause();
            assertProblem(lost, 409, "conflict");
            ctx.completeNow();
        }));
    }

    @Test
    void theBootstrapKeyStoresAsTheSystemOrganisation(VertxTestContext ctx) {
        String key = "bootstrap-key-0123456789abcdefghijklmnop";
        ApiKeyRepository keys = core.services().apiKeys();
        keys.upsertWellKnown(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID,
                        nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_NAME, key, ApiKeyRepository.Role.ADMIN,
                        nzi.fhir.validator.core.Constant.SYSTEM_ORG_UUID, nzi.fhir.validator.core.Constant.SYS_ORG_API_KEY_EXPIRES_AT)
                .compose(v -> keys.findActive(key))
                .compose(info -> {
                    Caller bootstrap = info.orElseThrow().caller();
                    ctx.verify(() -> assertTrue(bootstrap.system()));
                    return service().registerIg(pkg("up.sys", "1.0.0", "sys"), false, "UPLOAD", PackageOwnership.of(bootstrap, true));
                })
                .compose(p -> row("up.sys", "1.0.0"))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    assertTrue(row.startsWith(Organisations.SYSTEM_ID + "|true"), row);
                    ctx.completeNow();
                })));
    }

    @Test
    void anonymousCallersCanNeverStore() {
        assertThrows(IllegalArgumentException.class, () -> PackageOwnership.of(Caller.ANONYMOUS, true));
    }
}
