package nzi.fhir.validator.core.validator;

import io.vertx.core.CompositeFuture;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 013 US2 (FR-005, FR-006, SC-003): a regex that runs away on its input is still reported, in bounded time, and
 * does not hold the instance up. The fixture's constraint times out once per trap value, about 0.5 s each, on every
 * attempt.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class RegexTimeoutIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String TRAP = TestPackages.REGEX_TRAP + "#1.0.0";

    private CoreHarness core;
    private final Caller system = Caller.system("system");

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        RegexRecheck.resetSharedStateForTests();
        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    core = h;
                    return TestPackages.storePackage(h.services(), TestPackages.regexTrapPackage());
                })
                .compose(v -> core.services().configSync().syncCatalogNow())
                .compose(v -> core.services().igPackageService().include(system, R4, TestPackages.REGEX_TRAP, "1.0.0", Optional.empty()))
                .<Void>mapEmpty()
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    /** One validation against the trap context, with how long it took (ms). */
    private record Timed(ValidatorSnapshot.Outcome outcome, long ms) {
    }

    private Future<Timed> validate(String body) {
        long t0 = System.nanoTime();
        return core.services().validationService().validate(system, R4, IgRequest.parse(TRAP), body, SupportedContentType.JSON,
                        List.of(), Function.identity())
                .map(o -> new Timed(o, (System.nanoTime() - t0) / 1_000_000));
    }

    private static long timeouts(ValidatorSnapshot.Outcome o) {
        return o.result().getMessages().stream().filter(RegexTimeoutIssue::is).count();
    }

    @Test
    void aRunawayRegexIsReportedAsTheEngineReportsItInBoundedTime(VertxTestContext ctx) {
        validate(TestPackages.regexTrapPatient(1)).onComplete(ctx.succeeding(t -> ctx.verify(() -> {
            assertEquals(1, timeouts(t.outcome()), "a completed result, the timeout in it");
            assertFalse(t.outcome().result().isSuccessful(), "the constraint's error (FR-005)");
            // Three attempts of about 0.5 s each (SC-003: within 3 x the single validation time, plus margin).
            assertTrue(t.ms() >= 1_300 && t.ms() < 3 * 700 + 1_000, "took " + t.ms() + " ms");
            ctx.completeNow();
        })));
    }

    @Test
    void manyTimeoutsAreInputAndGetNoQuietRecheck(VertxTestContext ctx) {
        validate(TestPackages.regexTrapPatient(5)).onComplete(ctx.succeeding(t -> ctx.verify(() -> {
            assertEquals(5, timeouts(t.outcome()));
            // Two attempts of 5 x 0.5 s: the quiet re-check is skipped (more than 3 timeouts).
            assertTrue(t.ms() >= 4_500 && t.ms() < 9_000, "took " + t.ms() + " ms");
            ctx.completeNow();
        })));
    }

    @Test
    void trapRequestsNeverHoldNormalRequestsBack(VertxTestContext ctx) {
        List<Future<Timed>> traps = new ArrayList<>();
        List<Future<Timed>> normal = new ArrayList<>();
        // Within the harness's admission limit: a refusal for admission is not what this test is about.
        for (int i = 0; i < 4; i++) {
            traps.add(validate(TestPackages.regexTrapPatient(1)));
        }
        for (int i = 0; i < 8; i++) {
            normal.add(validate(TestPackages.minimalPatient()));
        }
        Future.all(new ArrayList<Future<?>>(traps)).compose(a -> Future.all(new ArrayList<Future<?>>(normal)))
                .onComplete(ctx.succeeding(a -> ctx.verify(() -> {
                    for (Future<Timed> f : normal) {
                        assertEquals(0, timeouts(f.result().outcome()));
                        assertTrue(f.result().outcome().result().isSuccessful() || f.result().outcome().result().getMessages()
                                .stream().noneMatch(m -> m.getSeverity().ordinal() >= ca.uhn.fhir.validation.ResultSeverityEnum.ERROR.ordinal()),
                                "a normal request is answered valid");
                    }
                    for (Future<Timed> f : traps) {
                        assertEquals(1, timeouts(f.result().outcome()), "every trap is answered (never 503)");
                    }
                    ctx.completeNow();
                })));
    }

    /**
     * US2 scenario 3: a long validation of a hostile resource is running (30 values, about 15 s; its own re-checks don't
     * fit the budget). A request that needs a quiet re-check gives up within about 1 s (reason busy), and normal
     * requests sent meanwhile are each answered within 2 s.
     */
    @Test
    void aLongHostileValidationInFlightNeverStallsTheInstance(Vertx vertx, VertxTestContext ctx) {
        Future<Timed> hostile = validate(TestPackages.regexTrapPatient(30));
        Future<Void> started = Future.future(p -> vertx.setTimer(1_000, id -> p.complete()));
        started.compose(v -> {
                    Future<Timed> needsQuiet = validate(TestPackages.regexTrapPatient(1));
                    List<Future<Timed>> normal = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        normal.add(validate(TestPackages.minimalPatient()));
                    }
                    return Future.all(needsQuiet, Future.all(new ArrayList<Future<?>>(normal)))
                            .map(all -> {
                                List<Timed> out = new ArrayList<>();
                                out.add(needsQuiet.result());
                                normal.forEach(f -> out.add(f.result()));
                                return out;
                            });
                })
                .onComplete(ctx.succeeding(results -> ctx.verify(() -> {
                    Timed quiet = results.get(0);
                    assertEquals(1, timeouts(quiet.outcome()), "answered with its last result");
                    assertTrue(quiet.ms() < 3_500, "the request needing a quiet re-check took " + quiet.ms() + " ms");
                    for (Timed n : results.subList(1, results.size())) {
                        assertTrue(n.ms() < 2_000, "a normal request took " + n.ms() + " ms");
                    }
                    assertFalse(hostile.isComplete(), "the hostile validation is still running");
                    hostile.onComplete(ar -> ctx.completeNow());
                })));
    }

    /**
     * Spec 013 T029 (FR-006, US2 scenario 3): a quiet re-check of a resource with 3 genuine timeouts runs about 1.5 s;
     * its exclusive permit is a 1 s lease, so normal requests sent all along are each held back about 1 s at most.
     */
    @Test
    void aSlowQuietRecheckHoldsOtherRequestsBackForAtMostItsLease(Vertx vertx, VertxTestContext ctx) {
        Future<Timed> trap = validate(TestPackages.regexTrapPatient(3));
        List<Future<Timed>> normal = new java.util.concurrent.CopyOnWriteArrayList<>();
        long timer = vertx.setPeriodic(200, id -> { // at most about 6 pile up in a 1 s hold: within admission
            if (!trap.isComplete()) {
                normal.add(validate(TestPackages.minimalPatient()));
            }
        });
        trap.compose(t -> {
                    vertx.cancelTimer(timer);
                    return Future.all(new ArrayList<Future<?>>(normal)).map(t);
                })
                .onComplete(ctx.succeeding(t -> ctx.verify(() -> {
                    assertEquals(3, timeouts(t.outcome()), "persistent after three attempts");
                    assertTrue(t.ms() >= 4_000, "three attempts of 3 x 0.5 s, the last one quiet: " + t.ms() + " ms");
                    assertTrue(normal.size() >= 15, "normal requests sent during the trap: " + normal.size());
                    long worst = normal.stream().mapToLong(f -> f.result().ms()).max().orElse(0);
                    // Without the lease the request arriving as the quiet re-check starts waits its whole ~1.5 s.
                    assertTrue(worst < 1_250, "a normal request was held back " + worst + " ms (lease 1 s)");
                    ctx.completeNow();
                })));
    }

    /** No validation is refused with 503 because of a re-check (edge case "Admission"). */
    @Test
    void aRecheckIsNeverRefusedForAdmission(VertxTestContext ctx) {
        List<Future<Timed>> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            all.add(validate(TestPackages.regexTrapPatient(1)));
        }
        Future.join(new ArrayList<Future<?>>(all)).onComplete(ar -> ctx.verify(() -> {
            for (Future<Timed> f : all) {
                assertTrue(f.succeeded(), () -> "refused: " + (f.cause() instanceof ProblemException p ? p.status() + " " + p.detail() : f.cause()));
            }
            ctx.completeNow();
        }));
    }

    // ---- US3: counters and the log line (FR-008, FR-009, SC-005) ----

    private static double metric(String scrape, String name, String... labels) {
        return scrape.lines().filter(l -> l.startsWith(name + "{"))
                .filter(l -> java.util.Arrays.stream(labels).allMatch(l::contains))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }

    @Test
    void aPersistentTimeoutIsCountedOnceAndLoggedWithoutContent(VertxTestContext ctx) {
        java.util.List<nzi.fhir.validator.core.usage.UsageRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        nzi.fhir.validator.core.usage.UsageMeter meter = nzi.fhir.validator.core.usage.UsageMeter.start("req-013", system,
                "127.0.0.1", "R4", 100, records::add);
        nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start();
        core.services().validationService().validate(system, R4, IgRequest.parse(TRAP), TestPackages.regexTrapPatient(1),
                        SupportedContentType.JSON, List.of(), meter, Function.identity())
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    meter.responseEnded(200, false, false);
                    logs.close();
                    String scrape = core.services().metrics().scrape();
                    assertEquals(1.0, metric(scrape, "fv_validation_regex_timeouts_total", "fhir_version=\"R4\"",
                            "outcome=\"persistent\"", "reason=\"input\""), scrape);
                    assertEquals(1.0, metric(scrape, "fv_validation_regex_rechecks_total", "fhir_version=\"R4\"", "kind=\"plain\""));
                    assertEquals(1.0, metric(scrape, "fv_validation_regex_rechecks_total", "fhir_version=\"R4\"", "kind=\"quiet\""));
                    assertEquals(1.0, metric(scrape, "fv_validations_total", "version=\"R4\""), "FR-009: one validation");
                    // LogCapture sees an event once per logger config it passes through: count distinct lines.
                    List<String> warn = logs.from(RegexRecheck.class.getName()).stream()
                            .filter(e -> e.message().startsWith("Regex timeout persisted")).map(e -> e.message()).distinct().toList();
                    assertEquals(List.of("Regex timeout persisted for R4:" + TRAP + " after 3 attempt(s) (reason input)"), warn);
                    assertFalse(warn.get(0).contains(TestPackages.REGEX_TRAP_VALUE) || warn.get(0).contains(TestPackages.RUNAWAY_REGEX),
                            "no value or pattern in the log");
                    assertEquals(1, records.size(), "one usage record");
                    assertTrue(records.get(0).validateMs() >= 1_300, "the record's duration covers the three attempts: "
                            + records.get(0).validateMs() + " ms");
                    ctx.completeNow();
                })));
    }
}

