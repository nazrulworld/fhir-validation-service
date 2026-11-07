package nzi.fhir.validator.core.validator;

import io.micrometer.core.instrument.Counter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 005 FR-013, SC-003, SC-004: the access check runs before any build, and a context built for one organisation
 * is never served to another that may not use it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class OwnershipOnDemandIT extends BaseTestContainer {
    private static final String P = "http://example.org/fhir/StructureDefinition/owned-on-demand";
    private static final String IG = "own.od#1.0.0";
    private static final SnapshotKey KEY = SnapshotKey.of(SupportedFhirVersion.R4, IG);

    private CoreHarness core;
    private Caller a;
    private Caller b;

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
                    UUID orgB = cf.resultAt(1);
                    a = Caller.of(orgA, "orga", ApiKeyRepository.Role.VALIDATE);
                    b = Caller.of(orgB, "orgb", ApiKeyRepository.Role.VALIDATE);
                    return TestPackages.storePackage(core.services(), TestPackages.profilePackage("own.od", "1.0.0",
                                    TestPackages.requiredPatientElements(P, "birthDate")))
                            .compose(v -> TestPackages.setPackageAccess(core.services().pool(), IG, orgA, false));
                })
                .compose(v -> core.services().configSync().syncCatalogNow())
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<ValidatorSnapshot.Outcome> validate(Caller who) {
        return core.services().validationService().validate(who, SupportedFhirVersion.R4, IgReference.parse(IG),
                TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(), Function.identity());
    }

    private double builds() {
        Counter c = core.services().metrics().registry().find("fv.validator.context.builds").tags("outcome", "success").counter();
        return c == null ? 0 : c.count();
    }

    private static void assertNotStored(Throwable err) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(404, p.status());
        assertEquals(ProblemException.igNotStored(IG).detail(), p.detail());
    }

    /** (a) A refused caller never triggers a build (SC-003). */
    @Test
    void aRefusedCallerTriggersNoBuild(VertxTestContext ctx) {
        double before = builds();
        validate(b).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertNotStored(err);
            assertEquals(before, builds(), "no build was admitted");
            assertNull(core.services().validatorRegistry().kind(KEY), "no slot was created");
            ctx.completeNow();
        })));
    }

    /** (b) A context A built on demand from its private package is never served to B (SC-004). */
    @Test
    void aContextBuiltForTheOwnerIsNeverServedToOthers(VertxTestContext ctx) {
        validate(a)
                .compose(o -> {
                    ctx.verify(() -> {
                        assertEquals(IG, o.igPackage());
                        assertEquals("orga", o.contextAccess().ownerShortName());
                        assertFalse(o.contextAccess().shareable());
                        assertEquals(ValidatorRegistry.Kind.ON_DEMAND, core.services().validatorRegistry().kind(KEY));
                    });
                    return validate(b);
                })
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertNotStored(err);
                    ctx.completeNow();
                })));
    }

    /** (c) An included context that is private to A is refused for B, whatever the package's own flag. */
    @Test
    void aPrivatePinnedContextIsRefusedForOthers(VertxTestContext ctx) {
        TestPackages.setPackageAccess(core.services().pool(), IG, a.orgId(), true)
                .compose(v -> core.services().pool().withTransaction(tx -> ConfigRevisionRepository.lockForUpdate(tx, SupportedFhirVersion.R4)
                        .compose(row -> ConfigRevisionRepository.updateIgSet(tx, SupportedFhirVersion.R4, List.of(IG), IG,
                                ConfigRevisionRepository.KIND_IG, a.orgId(), false))))
                .compose(rev -> core.services().configSync().syncNow(SupportedFhirVersion.R4))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> {
                    ctx.verify(() -> assertEquals(ValidatorRegistry.Kind.PINNED, core.services().validatorRegistry().kind(KEY)));
                    return validate(a);
                })
                .compose(o -> validate(b).transform(ar -> Future.succeededFuture(ar.cause())))
                .compose(err -> {
                    ctx.verify(() -> assertNotStored(err));
                    return validate(Caller.ANONYMOUS).transform(ar -> Future.succeededFuture(ar.cause()));
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertNotStored(err);
                    ctx.completeNow();
                })));
    }

    /** (d) Making the package shareable lets B use the context A built, with no rebuild. */
    @Test
    void sharingTakesEffectWithoutARebuild(Vertx vertx, VertxTestContext ctx) {
        validate(a)
                .compose(o -> {
                    double before = builds();
                    return TestPackages.setPackageAccess(core.services().pool(), IG, a.orgId(), true)
                            .compose(v -> Await.until(vertx, 10_000, "B may use the shared package",
                                    () -> validate(b).map(x -> true).otherwise(false)))
                            .map(ms -> before);
                })
                .onComplete(ctx.succeeding(before -> ctx.verify(() -> {
                    assertEquals(before, builds(), "the context A built is reused");
                    ctx.completeNow();
                })));
    }

    /** The reverse: making a shared package private refuses B again within the propagation window. */
    @Test
    void makingAPackagePrivateRefusesOthersAgain(Vertx vertx, VertxTestContext ctx) {
        TestPackages.setPackageAccess(core.services().pool(), IG, a.orgId(), true)
                .compose(v -> Await.until(vertx, 10_000, "B may use the shared package",
                        () -> validate(b).map(x -> true).otherwise(false)))
                .compose(ms -> TestPackages.setPackageAccess(core.services().pool(), IG, a.orgId(), false))
                .compose(v -> Await.until(vertx, 10_000, "B is refused again",
                        () -> validate(b).map(x -> false).otherwise(err -> err instanceof ProblemException p && p.status() == 404)))
                .compose(ms -> validate(a))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals(Optional.of(IG), Optional.ofNullable(o.igPackage()));
                    ctx.completeNow();
                })));
    }
}
