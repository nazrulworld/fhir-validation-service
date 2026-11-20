package nzi.fhir.validator.testsupport;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retry rule for HL7 core's regex timeout ({@link RegexTimeouts}): a check that fails only because the timeout
 * fired under load is retried, up to {@link RegexTimeouts#ATTEMPTS} attempts; any other difference fails at once.
 */
class RegexTimeoutsTest {
    private static final String TIMEOUT = "extra: Exception evaluating regex '\\S*' on type uri: Regex evaluation timed out after ";

    @Test
    void aRegexTimeoutIsRetriedUntilTheCheckPasses() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        RegexTimeouts.retry(() -> {
            if (runs.incrementAndGet() < 3) {
                throw new AssertionError(TIMEOUT);
            }
        });
        assertEquals(3, runs.get());
    }

    @Test
    void aTimeoutOnEveryAttemptFailsAndKeepsTheEarlierAttempts() {
        AtomicInteger runs = new AtomicInteger();
        AssertionError e = assertThrows(AssertionError.class, () -> RegexTimeouts.retry(() -> {
            throw new AssertionError(TIMEOUT + " (attempt " + runs.incrementAndGet() + ")");
        }));
        assertEquals(RegexTimeouts.ATTEMPTS, runs.get());
        assertTrue(e.getMessage().endsWith("(attempt 3)"), e.getMessage());
        assertEquals(RegexTimeouts.ATTEMPTS - 1, e.getSuppressed().length, "the earlier attempts are kept");
        assertTrue(e.getSuppressed()[0].getMessage().endsWith("(attempt 1)"));
    }

    @Test
    void anyOtherDifferenceFailsAtOnce() {
        AtomicInteger runs = new AtomicInteger();
        AssertionError e = assertThrows(AssertionError.class, () -> RegexTimeouts.retry(() -> {
            runs.incrementAndGet();
            throw new AssertionError("missing: Patient.birthDate: minimum required = 1");
        }));
        assertEquals(1, runs.get());
        assertEquals(0, e.getSuppressed().length);
    }

    @Test
    void bothTimeoutTextsAreRecognised() {
        assertTrue(RegexTimeouts.mentioned("Regex evaluation timed out after 500 ms"));
        assertTrue(RegexTimeouts.mentioned("The regex '\\S*' could not be executed"));
        assertFalse(RegexTimeouts.mentioned("The value 'x' does not match the regex"));
        assertFalse(RegexTimeouts.mentioned(null));
    }

    /** Detection matches HL7's English messages, so the test JVM must not localise them (surefire argLine). */
    @Test
    void theTestJvmRunsInEnglish() {
        assertEquals("en", Locale.getDefault().getLanguage(), "set -Duser.language=en for the test JVM");
    }

    /**
     * A regex timeout in a golden comparison never hides a real difference in the same outcome: every missing and
     * extra issue is reported.
     */
    @Test
    void aGoldenDifferenceListsEveryIssue() {
        JsonObject a = issue("error", "Patient.birthDate: minimum required = 1");
        JsonObject b = issue("warning", "Constraint failed: dom-6");
        JsonObject timeout = issue("error", "Exception evaluating regex '\\S*' on type uri: Regex evaluation timed out after ");
        JsonObject regression = issue("error", "Unknown profile http://example.org/x");
        String outcome = new JsonObject().put("resourceType", "OperationOutcome")
                .put("issue", new JsonArray().add(outcomeIssue(timeout)).add(outcomeIssue(a)).add(outcomeIssue(regression)))
                .encode();
        JsonArray expected = GoldenOutcomes.project(new JsonObject().put("resourceType", "OperationOutcome")
                .put("issue", new JsonArray().add(outcomeIssue(a)).add(outcomeIssue(b))).encode());
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenOutcomes.assertSame(expected, outcome, "case"));
        assertTrue(RegexTimeouts.timedOut(e), e.getMessage());
        assertTrue(e.getMessage().contains("Unknown profile http://example.org/x"), e.getMessage());
        assertTrue(e.getMessage().contains("missing: ") && e.getMessage().contains("dom-6"), e.getMessage());
    }

    private static JsonObject issue(String severity, String text) {
        return new JsonObject().put("severity", severity).put("text", text);
    }

    private static JsonObject outcomeIssue(JsonObject issue) {
        return new JsonObject().put("severity", issue.getString("severity")).put("code", "processing")
                .put("diagnostics", issue.getString("text"));
    }
}
