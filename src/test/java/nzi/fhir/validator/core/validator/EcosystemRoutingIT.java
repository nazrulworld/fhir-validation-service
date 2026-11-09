package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.terminology.RegistryResolver;
import nzi.fhir.validator.core.terminology.TerminologyClients;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologyServices;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.core.terminology.TestTerminology;
import nzi.fhir.validator.testsupport.StubTerminologyServer;
import nzi.fhir.validator.testsupport.StubTxRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static nzi.fhir.validator.core.validator.TerminologyRoutingIT.LOINC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 US1 (FR-016, FR-017, SC-001 stubbed, SC-004) and US3 scenario 2: in mode ecosystem each code system goes to
 * the server the registry names for it. Two stub terminology servers stand in for the registry's servers: the recorded
 * LOINC and SNOMED CT answers of 2026-11-09 are rewritten so that tx.fhir.org is stub A and the Swiss server is stub B.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class EcosystemRoutingIT {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String SNOMED = "http://snomed.info/sct";

    private StubTerminologyServer a;
    private StubTerminologyServer b;
    private StubTxRegistry registry;
    private final AtomicLong nanos = new AtomicLong();

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        a = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate").code(SNOMED, "38341003", "Hypertension");
        b = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate").code(SNOMED, "38341003", "Hypertension");
        registry = new StubTxRegistry();
        a.start(vertx).compose(x -> b.start(vertx)).compose(x -> {
            registry.replace("http://tx.fhir.org/r4", a.base()).replace("https://tx.fhir.ch/r4", b.base())
                    .resolve("R4", LOINC, "resolve-R4-loinc.json").resolve("R4", SNOMED, "resolve-R4-snomed.json");
            return registry.start(vertx);
        }).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        a.stop().eventually(() -> b.stop()).eventually(() -> registry.stop()).onComplete(ar -> ctx.completeNow());
    }

    private record Setup(ValidatorSnapshot snapshot, TestTerminology.MutableAllowlist allowlist) {
    }

    /** Stub A plays tx.fhir.org (the default server); both stubs are allowlisted unless {@code onlyB}. */
    private Setup ecosystem(String contextServer, boolean onlyB) throws Exception {
        TestTerminology.MutableAllowlist allowlist = onlyB ? TestTerminology.allowlist(R4, b.base())
                : TestTerminology.allowlist(R4, a.base(), b.base());
        allowlist.defaultServer(R4, a.base());
        TerminologyClients clients = TestTerminology.clients(allowlist, 2_000);
        RegistryResolver resolver = TestTerminology.resolver(registry.base(), nanos::get);
        TerminologyServices services = TestTerminology.services(allowlist, clients, resolver, 10_000);
        return new Setup(TerminologyRoutingIT.snapshot(new TerminologySettings(TerminologyMode.ECOSYSTEM, contextServer), services),
                allowlist);
    }

    private static String condition(String code) {
        return new io.vertx.core.json.JsonObject().put("resourceType", "Condition").put("id", "c1")
                .put("subject", new io.vertx.core.json.JsonObject().put("reference", "Patient/p1"))
                .put("code", new io.vertx.core.json.JsonObject().put("coding", new io.vertx.core.json.JsonArray()
                        .add(new io.vertx.core.json.JsonObject().put("system", SNOMED).put("code", code)))).encode();
    }

    private static ValidatorSnapshot.Outcome validateNoProfile(Vertx vertx, ValidatorSnapshot s, String body) throws Exception {
        return vertx.executeBlocking(() -> s.validate(body, nzi.fhir.validator.core.enums.SupportedContentType.JSON, java.util.List.of()))
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** US1-1, US1-2: LOINC goes to the server the registry prefers (tx.fhir.org = A); a repeat costs nothing. */
    @Test
    void loincIsRoutedThroughTheRegistryAndCached(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        ValidatorSnapshot.Outcome bad = TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "9999-9"));
        assertTrue(TerminologyRoutingIT.hasError(bad), bad.result().getMessages().toString());
        assertTrue(a.hits() >= 1);
        assertEquals(0, b.hits(), "the other registry server is not asked");
        int resolves = registry.resolveHits();
        int calls = a.hits();
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "9999-9"));
        assertEquals(resolves, registry.resolveHits(), "SC-004: no extra registry lookup");
        assertEquals(calls, a.hits(), "SC-004: no extra server call");
    }

    /** US1-3: local and common codes never reach the registry. */
    @Test
    void localCodesNeverReachTheRegistry(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(TestPackages.TX_LOCAL_SYSTEM, "local-a"));
        assertEquals(0, registry.resolveHits());
        assertEquals(0, a.hits() + b.hits());
    }

    /** US3-2: the context's own server replaces the default server in every answer. */
    @Test
    void theContextsOwnServerReplacesTheDefaultServer(Vertx vertx) throws Exception {
        Setup s = ecosystem(b.base(), false);
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertTrue(b.hits() >= 1);
        assertEquals(0, a.hits(), "tx.fhir.org (A) was replaced by the context's server (B)");
    }

    /** SC-001 (stubbed): SNOMED CT is routed too; an invalid concept is an error, a valid one isn't. */
    @Test
    void snomedIsRoutedAndChecked(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        ValidatorSnapshot.Outcome bad = validateNoProfile(vertx, s.snapshot(), condition("99999999999"));
        assertTrue(bad.result().getMessages().stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                && m.getLocationString().startsWith("Condition.code")), bad.result().getMessages().toString());
        ValidatorSnapshot.Outcome good = validateNoProfile(vertx, s.snapshot(), condition("38341003"));
        assertTrue(good.result().getMessages().stream().noneMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                && m.getLocationString().startsWith("Condition.code")), good.result().getMessages().toString());
        assertTrue(a.hits() >= 2);
    }

    /** US1-4: with no allowlisted server for the system at all, the code is "not checked" (a warning). */
    @Test
    void withNoPermittedServerTheCodeIsNotChecked(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        s.allowlist().allow(R4, java.util.List.of());
        ValidatorSnapshot.Outcome o = TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(0, a.hits() + b.hits());
        assertTrue(o.result().getMessages().stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.WARNING
                && m.getMessage().contains("No permitted terminology server for http://loinc.org")), o.result().getMessages().toString());
        assertTrue(o.result().getMessages().stream().noneMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                && m.getLocationString().startsWith("Observation.code")));
    }

    /** Registry /resolve down: the fallback is the default server; the failure is retried after 5 minutes. */
    @Test
    void aRegistryOutageFallsBackAndIsRetriedLater(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        registry.mode(StubTxRegistry.Mode.UNAVAILABLE);
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertTrue(a.hits() >= 1, "the default server (A) is the fallback");
        int lookups = registry.resolveHits();
        registry.mode(StubTxRegistry.Mode.NORMAL);
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "1234-5"));
        assertEquals(lookups, registry.resolveHits(), "the failure is remembered");
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(5) + 1);
        TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "2345-6"));
        assertTrue(registry.resolveHits() > lookups, "asked again after 5 minutes");
    }

    /** FR-026, constitution Security: client-supplied systems and codes are logged at DEBUG only, never at INFO or above. */
    @Test
    void codesAndSystemsNeverReachInfoLogs(Vertx vertx) throws Exception {
        Setup s = ecosystem(null, false);
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            a.error();
            TerminologyRoutingIT.validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "9999-9"));
            registry.mode(StubTxRegistry.Mode.UNAVAILABLE);
            validateNoProfile(vertx, s.snapshot(), condition("99999999999"));
            assertTrue(logs.events().stream()
                    .filter(e -> e.level().isMoreSpecificThan(org.apache.logging.log4j.Level.INFO))
                    .noneMatch(e -> e.message().contains("9999-9") || e.message().contains("99999999999")
                            || e.message().contains("loinc.org") || e.message().contains("snomed.info")),
                    "a code or system was logged at INFO or above");
        }
    }

    /**
     * SC-003 (spec 003 SC-002 kept for them): while a terminology context exists and is used, a context in mode none and
     * the base context make 0 outbound requests (no server, no registry).
     */
    @Test
    void modeNoneAndTheBaseContextStayOfflineNextToATerminologyContext(Vertx vertx) throws Exception {
        Setup routed = ecosystem(null, false);
        TerminologyServices shared = TestTerminology.services(routed.allowlist(), TestTerminology.clients(routed.allowlist(), 2_000),
                TestTerminology.resolver(registry.base(), nanos::get), 10_000);
        ValidatorSnapshot none = TerminologyRoutingIT.snapshot(TerminologySettings.NONE, shared);
        ValidatorSnapshot base = SnapshotBuilder.assemble(SnapshotKey.base(R4), 1, java.util.List.of(), java.util.Map.of(),
                java.util.List.of(), TerminologySettings.NONE, shared);
        TerminologyRoutingIT.validate(vertx, routed.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        a.clear();
        b.clear();
        int lookups = registry.resolveHits();
        TerminologyRoutingIT.validate(vertx, none, TestPackages.codedObservation(LOINC, "1111-1"));
        validateNoProfile(vertx, base, condition("99999999999"));
        validateNoProfile(vertx, base, TestPackages.codedObservation(LOINC, "2222-2"));
        assertEquals(0, a.hits() + b.hits(), "no terminology server call");
        assertEquals(lookups, registry.resolveHits(), "no registry lookup");
    }
}
