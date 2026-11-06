package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounds on on-demand contexts against real PostgreSQL (spec 004 FR-015, FR-016, SC-008, research R7): the count
 * limit evicts least recently used first, memory pressure evicts on-demand contexts but never pinned ones, a context
 * that cannot fit next to the pinned ones is 503 and one that can never fit is 413, and an evicted context still
 * serves the validation already using it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ContextEvictionIT extends BaseTestContainer {
    private static final String P = "http://example.org/fhir/StructureDefinition/evict";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private CoreHarness core;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        CoreHarness.start(vertx, connectOptions(), new JsonObject()
                        .put("ig", new JsonObject().put("maxOnDemand", 2).put("buildWaitMs", 30_000)))
                .compose(h -> {
                    core = h;
                    Future<Void> all = Future.succeededFuture();
                    for (String name : List.of("ev.a", "ev.b", "ev.c", "ev.pinned")) {
                        all = all.compose(v -> TestPackages.storePackage(h.services(), TestPackages.profilePackage(name, "1.0.0",
                                TestPackages.requiredPatientElements(P, "birthDate"))));
                    }
                    return all.compose(v -> h.services().igPackageService().include(R4, "ev.pinned", "1.0.0"));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (core != null) {
            core.services().heapBudget().setMaxHeap(() -> Runtime.getRuntime().maxMemory());
        }
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<ValidatorSnapshot.Outcome> validate(String ig) {
        return core.services().validationService().validate(R4, IgReference.parse(ig), TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(P), Function.identity());
    }

    private ValidatorRegistry registry() {
        return core.services().validatorRegistry();
    }

    private static SnapshotKey key(String ig) {
        return SnapshotKey.of(R4, ig);
    }

    /** Heap that is full right now: nothing fits unless memory is freed. */
    private void exhaustHeap() {
        core.services().heapBudget().setMaxHeap(
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + 1024 * 1024);
    }

    @Test
    void countLimitEvictsLeastRecentlyUsed(VertxTestContext ctx) {
        validate("ev.a#1.0.0")
                .compose(o -> validate("ev.b#1.0.0"))
                .compose(o -> validate("ev.a#1.0.0"))
                .compose(o -> validate("ev.c#1.0.0"))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertNull(registry().kind(key("ev.b#1.0.0")), "ev.b was least recently used");
                    assertEquals(ValidatorRegistry.Kind.ON_DEMAND, registry().kind(key("ev.a#1.0.0")));
                    assertEquals(ValidatorRegistry.Kind.ON_DEMAND, registry().kind(key("ev.c#1.0.0")));
                    assertEquals(2, registry().contextCount(R4, ValidatorRegistry.Kind.ON_DEMAND), "never above the limit");
                    assertEquals(ValidatorRegistry.Kind.PINNED, registry().kind(key("ev.pinned#1.0.0")));
                    ctx.completeNow();
                })));
    }

    @Test
    void memoryPressureEvictsOnDemandButNeverPinnedThenAnswers503(VertxTestContext ctx) {
        validate("ev.a#1.0.0")
                .compose(o -> {
                    exhaustHeap();
                    return validate("ev.b#1.0.0").transform(Future::succeededFuture);
                })
                .onComplete(ctx.succeeding(ar -> ctx.verify(() -> {
                    ProblemException p = assertInstanceOf(ProblemException.class, ar.cause());
                    assertEquals(503, p.status());
                    assertEquals("insufficient-capacity", p.type());
                    assertEquals("30", p.headers().get("Retry-After"));
                    assertNull(registry().kind(key("ev.a#1.0.0")), "the on-demand context was evicted to make room");
                    assertNull(registry().kind(key("ev.b#1.0.0")), "the failed build is not cached");
                    assertEquals(ValidatorRegistry.Status.ACTIVE, registry().status(key("ev.pinned#1.0.0")),
                            "pinned contexts are never evicted");
                    assertTrue(registry().isReady());
                    ctx.completeNow();
                })));
    }

    @Test
    void aContextLargerThanTheWholeHeapIs413(VertxTestContext ctx) {
        core.services().heapBudget().setMaxHeap(() -> 16L * 1024 * 1024);
        validate("ev.c#1.0.0").onComplete(ctx.failing(err -> ctx.verify(() -> {
            ProblemException p = assertInstanceOf(ProblemException.class, err);
            assertEquals(413, p.status());
            assertEquals("package-too-large", p.type());
            assertTrue(p.detail().contains("ev.c#1.0.0"), p.detail());
            ctx.completeNow();
        })));
    }

    @Test
    void anEvictedContextStillServesTheValidationUsingIt(VertxTestContext ctx) {
        registry().snapshot(key("ev.a#1.0.0"))
                .compose(snapshot -> {
                    assertTrue(registry().evictOne(null));
                    assertNull(registry().kind(key("ev.a#1.0.0")));
                    return core.services().executors().validation().executeBlocking(() ->
                            snapshot.validate(TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(P)), false);
                })
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"), "the in-flight validation completes normally");
                    ctx.completeNow();
                })));
    }
}
