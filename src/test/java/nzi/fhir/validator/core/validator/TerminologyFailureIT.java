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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.core.validator.TerminologyRoutingIT.LOINC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 FR-019, FR-019a, FR-011, SC-005, SC-005a, research R6, R7: an unavailable server, an open circuit, a
 * redirect, a spent request budget or a server that is no longer allowed each give a "not checked" <em>warning</em> on
 * the coded element, never an error, and a degraded answer is never served from the cache afterwards.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class TerminologyFailureIT {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private StubTerminologyServer stub;
    private StubTerminologyServer other;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        stub = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
        other = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
        stub.start(vertx).compose(s -> other.start(vertx)).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        stub.stop().eventually(() -> other.stop()).onComplete(ar -> ctx.completeNow());
    }

    private record Setup(ValidatorSnapshot snapshot, TestTerminology.MutableAllowlist allowlist, TerminologyClients clients) {
    }

    private Setup setup(long timeoutMs, long budgetMs) throws Exception {
        TestTerminology.MutableAllowlist allowlist = TestTerminology.allowlist(R4, stub.base(), other.base());
        TerminologyClients clients = TestTerminology.clients(allowlist, timeoutMs);
        TerminologyServices services = TestTerminology.services(allowlist, clients, RegistryResolver.DISABLED, budgetMs);
        return new Setup(TerminologyRoutingIT.snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()), services),
                allowlist, clients);
    }

    private static ValidatorSnapshot.Outcome validate(Vertx vertx, ValidatorSnapshot s, String body) throws Exception {
        return TerminologyRoutingIT.validate(vertx, s, body);
    }

    /** The issues about the coded element. */
    private static List<SingleValidationMessage> codeIssues(ValidatorSnapshot.Outcome o) {
        return o.result().getMessages().stream().filter(m -> m.getLocationString().startsWith("Observation.code")).toList();
    }

    private static void assertNotCheckedWarning(ValidatorSnapshot.Outcome o, String text) {
        List<SingleValidationMessage> issues = codeIssues(o);
        assertTrue(issues.stream().noneMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                || m.getSeverity() == ResultSeverityEnum.FATAL), "never an error: " + issues);
        assertTrue(issues.stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.WARNING && m.getMessage().contains(text)),
                "a warning containing '" + text + "': " + issues);
    }

    @Test
    void aServerThatIsDownGivesAWarningWithinTheTimeout(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 10_000);
        stub.reset();
        long started = System.currentTimeMillis();
        ValidatorSnapshot.Outcome o = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertNotCheckedWarning(o, "Terminology server unavailable; code http://loinc.org#8867-4 was not checked");
        assertTrue(System.currentTimeMillis() - started < 15_000, "SC-005: bounded by the timeout");
    }

    @Test
    void aSlowServerTimesOutAsUnavailable(Vertx vertx) throws Exception {
        Setup s = setup(500, 10_000);
        stub.delay(3_000);
        long started = System.currentTimeMillis();
        ValidatorSnapshot.Outcome o = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertNotCheckedWarning(o, "Terminology server unavailable");
        assertTrue(System.currentTimeMillis() - started < 3_000, "the call was cut at the 500 ms timeout");
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndTheServerIsSkipped(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 10_000);
        stub.error();
        for (int i = 0; i < 6 && !s.clients().circuitOpen(stub.base()); i++) {
            validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, (20000 + i) + "-1"));
        }
        assertTrue(s.clients().circuitOpen(stub.base()), "five consecutive failures open the circuit");
        stub.clear();
        ValidatorSnapshot.Outcome o = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(0, stub.hits(), "an open circuit makes no call");
        assertNotCheckedWarning(o, "Terminology server unavailable");
    }

    /** FR-011: a redirect is never followed. */
    @Test
    void aRedirectIsNotFollowed(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 10_000);
        stub.redirectTo(other.base());
        ValidatorSnapshot.Outcome o = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(0, other.hits(), "the redirect target is never contacted");
        assertNotCheckedWarning(o, "was not checked");
    }

    /** FR-019a, SC-005a: once the request's terminology time is used up, no more calls are made. */
    @Test
    void theRequestBudgetStopsFurtherCalls(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 1_000);
        stub.delay(150);
        long started = System.currentTimeMillis();
        ValidatorSnapshot.Outcome o = vertx.executeBlocking(() -> s.snapshot().validate(TestPackages.loincBundle(40),
                SupportedContentType.JSON, List.of())).toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
        long waited = System.currentTimeMillis() - started;
        assertTrue(stub.hits() < 40, "calls stopped once the budget was spent: " + stub.hits());
        assertTrue(o.result().getMessages().stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.WARNING
                && m.getMessage().contains("Terminology time budget for this request was used up")), "budget warning present");
        assertTrue(o.result().getMessages().stream().noneMatch(m -> m.getMessage().contains("was not checked")
                && m.getSeverity() == ResultSeverityEnum.ERROR), "never an error");
        assertTrue(waited < 1_000 + 1_000 + 10_000, "budget + one timeout + normal validation time: " + waited);
    }

    /** Research R6: after the outage the same code is checked again, not served the cached warning. */
    @Test
    void aDegradedAnswerIsNotServedFromTheCacheAfterTheOutage(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 10_000);
        stub.error();
        assertNotCheckedWarning(validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "9999-9")), "was not checked");
        stub.normal();
        stub.clear();
        ValidatorSnapshot.Outcome after = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "9999-9"));
        assertTrue(stub.hits() >= 1, "asked again");
        assertTrue(codeIssues(after).stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR),
                "now reported from the server: " + codeIssues(after));
    }

    /** US4-4, FR-010 c: a server removed from the allowlist after the build is never contacted. */
    @Test
    void aServerRemovedFromTheAllowlistIsNotContacted(Vertx vertx) throws Exception {
        Setup s = setup(1_000, 10_000);
        s.allowlist().allow(R4, List.of(other.base()));
        ValidatorSnapshot.Outcome o = validate(vertx, s.snapshot(), TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(0, stub.hits());
        assertNotCheckedWarning(o, "No permitted terminology server for http://loinc.org; code was not checked");
    }

    /** US4-4, T058: a context built while its server is not allowlisted fails closed for terminology only. */
    @Test
    void aContextBuiltWithADisallowedServerUsesTheDeniedModule(Vertx vertx) throws Exception {
        TestTerminology.MutableAllowlist allowlist = TestTerminology.allowlist(R4, other.base());
        TerminologyClients clients = TestTerminology.clients(allowlist, 1_000);
        ValidatorSnapshot s = TerminologyRoutingIT.snapshot(new TerminologySettings(TerminologyMode.SERVER, stub.base()),
                TestTerminology.services(allowlist, clients, RegistryResolver.DISABLED, 10_000));
        ValidatorSnapshot.Outcome o = validate(vertx, s, TestPackages.codedObservation(LOINC, "8867-4"));
        assertEquals(0, stub.hits());
        assertEquals(0, other.hits());
        assertNotCheckedWarning(o, "No permitted terminology server for http://loinc.org; code was not checked");
        ValidatorSnapshot.Outcome local = validate(vertx, s, TestPackages.codedObservation(TestPackages.TX_LOCAL_SYSTEM, "nope"));
        assertTrue(codeIssues(local).stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR),
                "local codes are still checked in-process");
    }
}
