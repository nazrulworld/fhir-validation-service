package nzi.fhir.validator.testsupport;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The golden-outcome comparison ({@link GoldenOutcomes}) and the test JVM it relies on. */
class GoldenOutcomesTest {
    /** The test JVM runs in English (surefire argLine), so tests that compare messages see stable texts. */
    @Test
    void theTestJvmRunsInEnglish() {
        assertEquals("en", Locale.getDefault().getLanguage(), "set -Duser.language=en for the test JVM");
    }

    /** A golden comparison reports every missing and extra issue, so one difference never hides another. */
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
        assertTrue(e.getMessage().contains("Regex evaluation timed out"), e.getMessage());
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
