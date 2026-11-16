package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testsupport.StubTerminologyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T079, research R11: two client pools on one shared {@code FhirContext} each use their own guarded HTTP client, whichever
 * installed the context's factory last; and no terminology request can be made outside {@link TerminologyClients#call}.
 */
@ExtendWith(VertxExtension.class)
class TerminologyClientsIsolationTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private final FhirContext ctx = FhirContext.forR4Cached();
    private StubTerminologyServer stub;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext test) {
        stub = new StubTerminologyServer("/r4").code("http://loinc.org", "8867-4", "Heart rate");
        stub.start(vertx).onComplete(test.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext test) {
        stub.stop().onComplete(ar -> test.completeNow());
    }

    private IValidationSupport.CodeValidationResult check(TerminologyClients clients) {
        IgAwareRemoteTerminologySupport client = clients.client(ctx, R4, stub.base());
        return clients.call(stub.base(), R4, () -> client.validateCode(new ValidationSupportContext(client),
                        new ConceptValidationOptions(), "http://loinc.org", "8867-4", null, null),
                reason -> TerminologyClients.notChecked(reason, "http://loinc.org", "8867-4"));
    }

    @Test
    void eachPoolUsesItsOwnGuardWhicheverInstalledLast() {
        TxServerAllowlistSource allowlist = TestTerminology.allowlist(R4, stub.base());
        TerminologyClients loopbackAllowed = new TerminologyClients(allowlist, NetworkGuard.forTests(), 2_000, 2);
        TerminologyClients strict = new TerminologyClients(allowlist, NetworkGuard.strict(), 2_000, 2);
        loopbackAllowed.client(ctx, R4, stub.base());
        strict.client(ctx, R4, stub.base());

        assertEquals(IValidationSupport.IssueSeverity.WARNING, check(strict).getSeverity(),
                "the strict pool's guard refuses the loopback stub: not checked");
        assertEquals(0, stub.hits());
        IValidationSupport.CodeValidationResult ok = check(loopbackAllowed);
        assertTrue(stub.hits() >= 1, "the test pool reaches the stub with its own guard");
        assertNotEquals(IValidationSupport.IssueSeverity.WARNING, ok.getSeverity());
        stub.clear();
        check(strict);
        assertEquals(0, stub.hits(), "still refused after the other pool's call");
    }

    @Test
    void noRequestBypassesTheCallChecks() {
        TerminologyClients clients = new TerminologyClients(TestTerminology.allowlist(R4, stub.base()), NetworkGuard.forTests(), 2_000, 2);
        IgAwareRemoteTerminologySupport client = clients.client(ctx, R4, stub.base());
        assertThrows(RuntimeException.class, () -> client.validateCode(new ValidationSupportContext(client),
                new ConceptValidationOptions(), "http://loinc.org", "8867-4", null, null));
        assertEquals(0, stub.hits());
    }

    /**
     * Spec 010 FR-015, research R12: failure tracking is per server. Five failures open B's circuit (B was named by a
     * request override); calls to A (a context default) still go through, and B is skipped without a call.
     */
    @Test
    void aFailingServerCannotBlockAnother(Vertx vertx) throws Exception {
        // Calls block: they run on the test thread, never on the event loop the stubs answer on.
        StubTerminologyServer b = new StubTerminologyServer("/r4").error();
        b.start(vertx).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            TxServerAllowlistSource allowlist = TestTerminology.allowlist(R4, stub.base(), b.base());
            TerminologyClients clients = new TerminologyClients(allowlist, NetworkGuard.forTests(), 2_000, 2);
            IgAwareRemoteTerminologySupport clientB = clients.client(ctx, R4, b.base());
            java.util.function.Supplier<IValidationSupport.CodeValidationResult> callB = () -> clients.call(b.base(), R4,
                    () -> clientB.validateCode(new ValidationSupportContext(clientB), new ConceptValidationOptions(),
                            "http://loinc.org", "8867-4", null, null),
                    reason -> TerminologyClients.notChecked(reason, "http://loinc.org", "8867-4"));
            for (int i = 0; i < CircuitBreaker.THRESHOLD; i++) {
                callB.get();
            }
            assertTrue(clients.circuitOpen(b.base()), "B's circuit is open");
            int bHits = b.hits();
            callB.get();
            assertEquals(bHits, b.hits(), "B is skipped without a call");
            assertFalse(clients.circuitOpen(stub.base()), "A's circuit is unaffected");
            IValidationSupport.CodeValidationResult ok = check(clients);
            assertNotEquals(IValidationSupport.IssueSeverity.WARNING, ok.getSeverity(), "A still answers: " + ok.getMessage());
            assertNotSame(clients.client(ctx, R4, stub.base()), clientB, "separate clients per server");
        } finally {
            b.stop().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
