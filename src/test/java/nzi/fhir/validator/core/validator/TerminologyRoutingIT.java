package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.terminology.RegistryResolver;
import nzi.fhir.validator.core.terminology.TerminologyClients;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologyServices;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.core.terminology.TestTerminology;
import nzi.fhir.validator.testsupport.StubTerminologyServer;
import nzi.fhir.validator.testsupport.TestPackages;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 US3 (server mode) and the routing basics shared with US1, against a local stub terminology server (research
 * R14): codes that can't be decided locally go to the server and are reported from its answer; local and common code
 * systems never leave the process (FR-013); the IG's ValueSet and CodeSystems are sent (FR-014, Q2); a repeated code is
 * answered from the cache (SC-004); no definition is ever fetched from the server (spec 003 FR-002). Snapshots are
 * built directly (no database), with test-only infrastructure that allows loopback.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class TerminologyRoutingIT {
    static final String LOINC = "http://loinc.org";
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private StubTerminologyServer stub;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        stub = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
        stub.start(vertx).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        stub.stop().onComplete(ar -> ctx.completeNow());
    }

    static NpmPackage terminologyIg() throws Exception {
        return NpmPackage.fromPackage(new ByteArrayInputStream(TestPackages.terminologyIg()));
    }

    static ValidatorSnapshot snapshot(TerminologySettings tx, TerminologyServices services) throws Exception {
        String ig = TestPackages.TX_IG_NAME + "#" + TestPackages.TX_IG_VERSION;
        return SnapshotBuilder.assemble(SnapshotKey.of(SupportedFhirVersion.R4, ig), 1, List.of(ig), Map.of(),
                List.of(terminologyIg()), tx, services);
    }

    private TerminologyServices services() {
        TestTerminology.MutableAllowlist allowlist = TestTerminology.allowlist(R4, stub.base());
        TerminologyClients clients = TestTerminology.clients(allowlist, 2_000);
        return TestTerminology.services(allowlist, clients, RegistryResolver.DISABLED, 10_000);
    }

    static List<SingleValidationMessage> about(ValidatorSnapshot.Outcome o, String text) {
        return o.result().getMessages().stream().filter(m -> (m.getMessage() + " " + m.getLocationString()).contains(text)).toList();
    }

    static boolean hasError(ValidatorSnapshot.Outcome o) {
        return o.result().getMessages().stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                || m.getSeverity() == ResultSeverityEnum.FATAL);
    }

    /** Runs on a worker thread, as production does (validation never runs on the event loop). */
    static ValidatorSnapshot.Outcome validate(Vertx vertx, ValidatorSnapshot s, String body) throws Exception {
        return vertx.executeBlocking(() -> s.validate(body, SupportedContentType.JSON, List.of(TestPackages.TX_OBSERVATION_PROFILE)))
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void anInvalidLoincCodeIsReportedFromTheServerAndAValidOneIsNot(Vertx vertx) throws Exception {
        ValidatorSnapshot s = snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()), services());
        ValidatorSnapshot.Outcome bad = validate(vertx, s, TestPackages.codedObservation(LOINC, "9999-9"));
        assertTrue(stub.hits() >= 1, "the stub was asked");
        assertTrue(hasError(bad), "an invalid code is an error: " + bad.result().getMessages());
        stub.clear();
        ValidatorSnapshot.Outcome good = validate(vertx, s, TestPackages.codedObservation(LOINC, "8867-4"));
        assertFalse(hasError(good), "a valid code: " + good.result().getMessages());
        assertEquals(0, stub.metadataHits(), "no /metadata call");
    }

    @Test
    void aRepeatedCodeIsAnsweredFromTheCache(Vertx vertx) throws Exception {
        TestTerminology.MutableAllowlist allowlist = TestTerminology.allowlist(R4, stub.base());
        TerminologyClients clients = TestTerminology.clients(allowlist, 2_000);
        java.util.concurrent.atomic.AtomicInteger cacheHits = new java.util.concurrent.atomic.AtomicInteger();
        clients.setListener(new TerminologyClients.Listener() {
            @Override
            public void codeCacheHit(SupportedFhirVersion version) {
                cacheHits.incrementAndGet();
            }
        });
        ValidatorSnapshot s = snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()),
                TestTerminology.services(allowlist, clients, RegistryResolver.DISABLED, 10_000));
        validate(vertx, s, TestPackages.codedObservation(LOINC, "8867-4"));
        int first = stub.hits();
        assertTrue(first >= 1);
        int hitsBefore = cacheHits.get();
        validate(vertx, s, TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(first, stub.hits(), "SC-004: 0 additional terminology calls");
        assertTrue(cacheHits.get() > hitsBefore, "T078, FR-026: the repeat is counted as a code-cache hit");
    }

    /** FR-013 and the research R4 verification: local and common code systems never reach the server. */
    @Test
    void localAndCommonCodeSystemsNeverLeaveTheProcess(Vertx vertx) throws Exception {
        ValidatorSnapshot s = snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()), services());
        ValidatorSnapshot.Outcome local = validate(vertx, s, TestPackages.codedObservation(TestPackages.TX_LOCAL_SYSTEM, "local-a"));
        ValidatorSnapshot.Outcome unknownLocal = validate(vertx, s, TestPackages.codedObservation(TestPackages.TX_LOCAL_SYSTEM, "nope"));
        String ucum = new io.vertx.core.json.JsonObject(TestPackages.codedObservation(TestPackages.TX_LOCAL_SYSTEM, "local-b"))
                .put("valueQuantity", new io.vertx.core.json.JsonObject().put("value", 70).put("unit", "kg")
                        .put("system", "http://unitsofmeasure.org").put("code", "kg")).encode();
        validate(vertx, s, ucum);
        assertEquals(0, stub.hits(), "local and UCUM codes are decided in-process: " + stub.paths());
        assertFalse(hasError(local), local.result().getMessages().toString());
        assertTrue(hasError(unknownLocal), "an unknown code of a complete local system is an error, decided locally");
    }

    /** Clarification Q2, research R3: the IG's ValueSet goes with the request, and its local CodeSystem as tx-resource. */
    @Test
    void theIgsValueSetAndCodeSystemAreSent(Vertx vertx) throws Exception {
        ValidatorSnapshot s = snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()), services());
        validate(vertx, s, TestPackages.codedObservation(LOINC, "8867-4"));
        List<Parameters> requests = stub.requests();
        assertFalse(requests.isEmpty());
        Parameters vsRequest = requests.stream().filter(p -> p.getParameter("valueSet") != null).findFirst()
                .orElseThrow(() -> new AssertionError("no request carried the IG's ValueSet: " + stub.rawBodies()));
        org.hl7.fhir.r4.model.ValueSet vs = (org.hl7.fhir.r4.model.ValueSet) vsRequest.getParameter("valueSet").getResource();
        assertEquals(TestPackages.TX_MIXED_VALUESET, vs.getUrl());
        List<String> txResources = vsRequest.getParameter().stream().filter(p -> p.getName().equals("tx-resource"))
                .map(p -> ((org.hl7.fhir.r4.model.CodeSystem) p.getResource()).getUrl()).toList();
        assertEquals(List.of(TestPackages.TX_LOCAL_SYSTEM), txResources);
    }

    /** FR-014: the server is only asked to check codes; no definition is fetched (only operations are called). */
    @Test
    void onlyOperationsAreCalledNeverDefinitionReads(Vertx vertx) throws Exception {
        ValidatorSnapshot s = snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()), services());
        validate(vertx, s, TestPackages.codedObservation(LOINC, "9999-9"));
        assertTrue(stub.paths().stream().allMatch(p -> p.contains("$validate-code") || p.contains("$lookup")), stub.paths().toString());
    }

    /** Mode none: the stub is never contacted (SC-003). */
    @Test
    void modeNoneNeverCallsAServer(Vertx vertx) throws Exception {
        ValidatorSnapshot s = snapshot(TerminologySettings.NONE, services());
        validate(vertx, s, TestPackages.codedObservation(LOINC, "9999-9"));
        assertEquals(0, stub.hits());
    }
}
