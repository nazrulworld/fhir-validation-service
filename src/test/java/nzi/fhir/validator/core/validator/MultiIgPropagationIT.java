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
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 012 SC-005, FR-019: a two-IG context and its aliases reach every instance within the propagation window, by
 * notification and by the poll fallback; an exclude removes them everywhere.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class MultiIgPropagationIT extends BaseTestContainer {
    private static final long PROPAGATION_MS = 10_000;
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String KEY = MULTI_A + "#1.0.0|" + MULTI_B + "#2.0.0";
    private static final String REVERSED = MULTI_B + "#2.0.0|" + MULTI_A + "#1.0.0";

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
                .compose(h -> {
                    b = h;
                    return TestPackages.storePackage(a.services(), TestPackages.multiA());
                })
                .compose(v -> TestPackages.storePackage(a.services(), TestPackages.multiB()))
                .compose(v -> a.services().configSync().syncCatalogNow())
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> stopA = a == null ? Future.succeededFuture() : a.stop();
        Future<Void> stopB = b == null ? Future.succeededFuture() : b.stop();
        Future.join(stopA, stopB).onComplete(ar -> vertxB.close().onComplete(v -> ctx.completeNow()));
    }

    /** The context {@code ig} is validated against on {@code on}, or {@code null} when it is refused. */
    private static Future<String> contextOn(CoreHarness on, String ig) {
        return on.services().validationService().validate(Caller.system("system"), R4, IgRequest.parse(ig),
                        TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(), Function.identity())
                .map(ValidatorSnapshot.Outcome::contextLabel)
                .otherwise((String) null);
    }

    private static Future<Boolean> servedOn(CoreHarness on, String ig) {
        return contextOn(on, ig).map(KEY::equals);
    }

    private static Future<Boolean> refusedOn(CoreHarness on, String ig) {
        return contextOn(on, ig).map(label -> label == null);
    }

    @Test
    void includeAndExcludeReachTheOtherInstance(Vertx vertx, VertxTestContext ctx) {
        TestPackages.includePair(a.services(), R4, MULTI_A, "1.0.0", MULTI_B, "2.0.0")
                .compose(r -> Future.all(
                        Await.until(vertx, PROPAGATION_MS, "B serves the key", () -> servedOn(b, KEY)),
                        Await.until(vertx, PROPAGATION_MS, "B serves the reversed pair", () -> servedOn(b, REVERSED)),
                        Await.until(vertx, PROPAGATION_MS, "B serves the first-IG alias", () -> servedOn(b, MULTI_A + "#1.0.0"))))
                .compose(all -> b.services().igPackageService().exclude(Caller.system("system"), R4, REVERSED))
                .compose(r -> Future.all(
                        Await.until(vertx, PROPAGATION_MS, "A stops serving the key", () -> refusedOn(a, KEY)),
                        Await.until(vertx, PROPAGATION_MS, "A drops the reversed alias", () -> refusedOn(a, REVERSED)),
                        Await.until(vertx, PROPAGATION_MS, "A drops the first-IG alias", () -> refusedOn(a, MULTI_A + "#1.0.0"))))
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void thePollFallbackPropagatesWithoutNotifications(Vertx vertx, VertxTestContext ctx) {
        b.services().configSync().setNotificationsEnabled(false);
        TestPackages.includePair(a.services(), R4, MULTI_A, "1.0.0", MULTI_B, "2.0.0")
                .compose(r -> Await.until(vertx, 3 * PROPAGATION_MS, "B serves the reversed pair by polling",
                        () -> servedOn(b, REVERSED)))
                .onComplete(ctx.succeeding(ms -> ctx.verify(() -> {
                    assertEquals(KEY, b.services().configSync().pinnedAccess(R4).aliases().target(REVERSED).orElseThrow());
                    ctx.completeNow();
                })));
    }
}
