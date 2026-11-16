package nzi.fhir.validator.testsupport;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec 010 SC-004: compares an OperationOutcome with a golden file recorded from 1.0.0 by {@code scripts/golden-010.sh}.
 * Both sides are reduced to the same projection: per issue and in order, only severity, code, expression, location,
 * diagnostics and {@code details.text}; the OperationOutcome's id, meta and narrative are ignored. Java object
 * identities that HAPI prints in a few messages ({@code ConceptSetFilterComponent@67c0c1aa}) differ on every run, 1.0.0
 * included, so they are replaced by {@code @#} on both sides.
 */
public final class GoldenOutcomes {
    /** Corpus root on the classpath. */
    public static final String CORPUS = "corpus/010";

    private GoldenOutcomes() {
    }

    /** The projection of an OperationOutcome in FHIR JSON. */
    public static JsonArray project(String operationOutcomeJson) {
        JsonObject outcome = new JsonObject(operationOutcomeJson);
        JsonArray projected = new JsonArray();
        JsonArray issues = outcome.getJsonArray("issue", new JsonArray());
        for (int i = 0; i < issues.size(); i++) {
            JsonObject issue = issues.getJsonObject(i);
            JsonObject details = issue.getJsonObject("details");
            projected.add(new JsonObject()
                    .put("severity", issue.getValue("severity"))
                    .put("code", issue.getValue("code"))
                    .put("expression", issue.getValue("expression"))
                    .put("location", issue.getValue("location"))
                    .put("diagnostics", stable(issue.getString("diagnostics")))
                    .put("details", details == null ? null : stable(details.getString("text"))));
        }
        return projected;
    }

    private static final java.util.regex.Pattern IDENTITY = java.util.regex.Pattern.compile("(\\b[A-Z][A-Za-z0-9$]*)@[0-9a-f]{4,8}\\b");

    /** {@code text} with Java object identities ({@code Type@1a2b3c4d}) replaced, so it is stable between runs. */
    static String stable(String text) {
        return text == null ? null : IDENTITY.matcher(text).replaceAll("$1@#");
    }

    /** The golden projection stored at {@code golden/<relative>} under the corpus. */
    public static JsonArray golden(Path corpusRoot, String relative) {
        try {
            JsonArray raw = new JsonArray(Files.readString(corpusRoot.resolve("golden").resolve(relative), StandardCharsets.UTF_8));
            JsonArray out = new JsonArray();
            for (int i = 0; i < raw.size(); i++) {
                JsonObject issue = raw.getJsonObject(i).copy();
                issue.put("diagnostics", stable(issue.getString("diagnostics")));
                issue.put("details", stable(issue.getString("details")));
                out.add(issue);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Fails with a message naming the first differing issue when {@code operationOutcomeJson} does not project to
     * {@code expected}.
     */
    public static void assertSame(JsonArray expected, String operationOutcomeJson, String label) {
        JsonArray actual = project(operationOutcomeJson);
        if (expected.equals(actual)) {
            return;
        }
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            Object e = i < expected.size() ? expected.getValue(i) : null;
            Object a = i < actual.size() ? actual.getValue(i) : null;
            if (e == null ? a != null : !e.equals(a)) {
                fail(label + ": issue " + i + " differs from 1.0.0 (" + expected.size() + " vs " + actual.size()
                        + " issues)\n  expected: " + e + "\n  actual:   " + a);
            }
        }
        fail(label + ": outcome differs from 1.0.0");
    }
}
