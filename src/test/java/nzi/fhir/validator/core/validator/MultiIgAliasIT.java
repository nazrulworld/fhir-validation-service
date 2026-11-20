package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_C;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 012 US3 (FR-012, FR-012a): a two-IG context found by its first IG alone.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class MultiIgAliasIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String A1 = MULTI_A + "#1.0.0";
    private static final String B2 = MULTI_B + "#2.0.0";
    private static final String KEY = A1 + "|" + B2;

    private CoreHarness core;
    private final Caller system = Caller.system("system");

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    core = h;
                    return TestPackages.storePackage(h.services(), TestPackages.multiA());
                })
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.multiB()))
                .compose(v -> TestPackages.storePackage(core.services(), TestPackages.multiC()))
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> TestPackages.includePair(core.services(), R4, MULTI_A, "1.0.0", MULTI_B, "2.0.0"))
                .<Void>mapEmpty()
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    /** The context a request for {@code ig} is validated against ({@code X-Validation-Context}). */
    private Future<String> contextFor(String ig) {
        return core.services().validationService().validate(system, R4, IgRequest.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(), Function.identity()).map(ValidatorSnapshot.Outcome::contextLabel);
    }

    private static <T> Future<Throwable> failure(Future<T> f) {
        return f.transform(ar -> ar.succeeded() ? Future.failedFuture("expected a failure, got " + ar.result())
                : Future.succeededFuture(ar.cause()));
    }

    @Test
    void theFirstIgAloneReachesTheTwoIgContext(VertxTestContext ctx) {
        contextFor(A1).onComplete(ctx.succeeding(label -> ctx.verify(() -> {
            assertEquals(KEY, label);
            ctx.completeNow();
        })));
    }

    @Test
    void aSingleIgContextTakesOverAndHandsBack(VertxTestContext ctx) {
        core.services().igPackageService().include(system, R4, MULTI_A, "1.0.0", java.util.Optional.empty())
                .compose(r -> contextFor(A1))
                .compose(label -> {
                    ctx.verify(() -> assertEquals(A1, label, "the single-IG context wins"));
                    return core.services().igPackageService().exclude(system, R4, A1);
                })
                .compose(r -> contextFor(A1))
                .onComplete(ctx.succeeding(label -> ctx.verify(() -> {
                    assertEquals(KEY, label, "the alias was never removed; it serves again");
                    ctx.completeNow();
                })));
    }

    @Test
    void theSecondIgIsNeverAnAlias(VertxTestContext ctx) {
        failure(contextFor(B2)).onComplete(ctx.succeeding(err -> ctx.verify(() -> {
            ProblemException p = assertInstanceOf(ProblemException.class, err);
            assertEquals(400, p.status());
            assertTrue(p.detail().contains("not included"), p.detail());
            ctx.completeNow();
        })));
    }

    @Test
    void aFirstIgStartsOneTwoIgContextOnly(VertxTestContext ctx) {
        failure(TestPackages.includePair(core.services(), R4, MULTI_A, "1.0.0", MULTI_C, "3.0.0"))
                .compose(err -> {
                    ctx.verify(() -> assertEquals(409, assertInstanceOf(ProblemException.class, err).status()));
                    return TestPackages.includePair(core.services(), R4, MULTI_B, "2.0.0", MULTI_C, "3.0.0");
                })
                .compose(r -> contextFor(B2))
                .onComplete(ctx.succeeding(label -> ctx.verify(() -> {
                    assertEquals(B2 + "|" + MULTI_C + "#3.0.0", label, "B starts its own two-IG context");
                    ctx.completeNow();
                })));
    }

    /** FR-012a: a name without a version, or latest, never reaches a two-IG context. */
    @Test
    void versionlessRequestsUseSingleIgContextsOnly(VertxTestContext ctx) {
        failure(contextFor(MULTI_A))
                .compose(err -> {
                    ctx.verify(() -> {
                        ProblemException p = assertInstanceOf(ProblemException.class, err);
                        assertEquals(400, p.status());
                        assertTrue(p.detail().contains("not included"), p.detail());
                    });
                    return core.services().igPackageService().include(system, R4, MULTI_A, "1.0.0", java.util.Optional.empty());
                })
                .compose(r -> Future.all(contextFor(MULTI_A), contextFor(MULTI_A + "#latest"), contextFor(A1)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    assertEquals(A1, all.resultAt(0));
                    assertEquals(A1, all.resultAt(1));
                    assertEquals(A1, all.resultAt(2));
                    ctx.completeNow();
                })));
    }
}
