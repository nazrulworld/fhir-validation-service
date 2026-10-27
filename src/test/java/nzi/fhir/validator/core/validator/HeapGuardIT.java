package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T131: an IG set too large for the heap is refused before it is committed, and a committed one that cannot be built
 * on this instance makes only that version unavailable (named in /health) instead of ending the process with an
 * OutOfMemoryError. The heap limit is lowered artificially so that every build is refused.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class HeapGuardIT extends BaseTestContainer {
    private static final String PACKAGE = "example.heavy#1.0.0";

    private CoreHarness core;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        CoreHarness.start(vertx, connectOptions(), new io.vertx.core.json.JsonObject())
                .compose(h -> {
                    core = h;
                    return h.services().igPackageService().registerIg(TestPackages.npmPackage("example.heavy", "1.0.0",
                            Map.of(), List.of(TestPackages.requiredPatientElements(
                                    "http://example.org/fhir/StructureDefinition/heavy", "birthDate").put("id", "heavy"))));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    /** No room for any build: the limit is what is in use now plus 1 MB. */
    private void exhaustHeap() {
        core.services().heapBudget().setMaxHeap(
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + 1024 * 1024);
    }

    private void restoreHeap() {
        core.services().heapBudget().setMaxHeap(() -> Runtime.getRuntime().maxMemory());
    }

    @Test
    void anIncludeThatDoesNotFitIsRefusedBeforeItIsCommitted(VertxTestContext ctx) {
        exhaustHeap();
        core.services().igPackageService().include(SupportedFhirVersion.R4, "example.heavy", "1.0.0")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    ProblemException problem = assertInstanceOf(ProblemException.class, err);
                    assertEquals(413, problem.status());
                    assertEquals("package-too-large", problem.type());
                    assertTrue(problem.detail().contains("FV_MEMORY_LIMIT"), problem.detail());
                    ConfigRevisionRepository.current(core.services().pool()).onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                        assertEquals(List.of(), rows.get(SupportedFhirVersion.R4).included(), "nothing was committed");
                        assertNotNull(core.services().validatorRegistry().current(SupportedFhirVersion.R4),
                                "R4 keeps serving its current configuration");
                        ctx.completeNow();
                    })));
                })));
    }

    @Test
    void aCommittedSetThatCannotBeBuiltMakesOnlyThatVersionUnavailable(Vertx vertx, VertxTestContext ctx) {
        ValidatorRegistry registry = core.services().validatorRegistry();
        exhaustHeap();
        // As if another instance with more memory had committed it.
        registry.apply(SupportedFhirVersion.R4, 1_000, List.of(PACKAGE))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(HeapBudget.InsufficientHeapException.class, err);
                    assertEquals(ValidatorRegistry.Status.STALE, registry.status(SupportedFhirVersion.R4));
                    ProblemException notReady = assertThrows(ProblemException.class,
                            () -> registry.current(SupportedFhirVersion.R4));
                    assertEquals(503, notReady.status());
                    String reason = registry.details().getJsonObject("R4").getString("reason");
                    assertTrue(reason.contains("MB of heap") && reason.contains("FV_MEMORY_LIMIT"), reason);
                    assertTrue(registry.notReadyReasons().stream().anyMatch(r -> r.startsWith("R4")));
                    for (SupportedFhirVersion other : SupportedFhirVersion.enabled()) {
                        if (other != SupportedFhirVersion.R4) {
                            assertEquals(ValidatorRegistry.Status.ACTIVE, registry.status(other), other + " keeps serving");
                            assertNotNull(registry.current(other));
                        }
                    }
                    // With enough heap the scheduled retry succeeds.
                    restoreHeap();
                    awaitActive(vertx, registry, System.currentTimeMillis() + 30_000)
                            .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                                assertEquals(List.of(PACKAGE), registry.current(SupportedFhirVersion.R4).resolvedPackages());
                                ctx.completeNow();
                            })));
                })));
    }

    private static Future<Void> awaitActive(Vertx vertx, ValidatorRegistry registry, long deadline) {
        if (registry.status(SupportedFhirVersion.R4) == ValidatorRegistry.Status.ACTIVE) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() > deadline) {
            return Future.failedFuture("R4 did not recover: " + registry.details());
        }
        Promise<Void> next = Promise.promise();
        vertx.setTimer(250, id -> awaitActive(vertx, registry, deadline).onComplete(next));
        return next.future();
    }
}
