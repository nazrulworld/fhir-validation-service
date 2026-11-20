package nzi.fhir.validator.core.validator;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retry rule of {@link SharedDefinitionsDifferentialIT}: a check that fails only because HL7 core's regex timeout
 * fired under load is retried, up to {@link SharedDefinitionsDifferentialIT#REGEX_TIMEOUT_ATTEMPTS} attempts; any other
 * difference fails at once.
 */
class RegexTimeoutRetryTest {
    private static final String TIMEOUT = "issue 3 differs: Exception evaluating regex '\\S*' on type uri: Regex evaluation timed out after ";

    @Test
    void aRegexTimeoutIsRetriedUntilTheCheckPasses() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        SharedDefinitionsDifferentialIT.checkAlone(() -> {
            if (runs.incrementAndGet() < 3) {
                throw new AssertionError(TIMEOUT);
            }
        });
        assertEquals(3, runs.get());
    }

    @Test
    void aTimeoutOnEveryAttemptFails() {
        AtomicInteger runs = new AtomicInteger();
        AssertionError e = assertThrows(AssertionError.class, () -> SharedDefinitionsDifferentialIT.checkAlone(() -> {
            runs.incrementAndGet();
            throw new AssertionError(TIMEOUT);
        }));
        assertEquals(SharedDefinitionsDifferentialIT.REGEX_TIMEOUT_ATTEMPTS, runs.get());
        assertTrue(e.getMessage().contains("Regex evaluation timed out"));
    }

    @Test
    void anyOtherDifferenceFailsAtOnce() {
        AtomicInteger runs = new AtomicInteger();
        assertThrows(AssertionError.class, () -> SharedDefinitionsDifferentialIT.checkAlone(() -> {
            runs.incrementAndGet();
            throw new AssertionError("issue 0 differs from 1.0.0: Patient.birthDate: minimum required = 1");
        }));
        assertEquals(1, runs.get());
    }

    @Test
    void bothTimeoutTextsAreRecognised() {
        assertTrue(SharedDefinitionsDifferentialIT.hasRegexTimeout("Regex evaluation timed out after 500 ms"));
        assertTrue(SharedDefinitionsDifferentialIT.hasRegexTimeout("The regex '\\S*' could not be executed"));
        assertFalse(SharedDefinitionsDifferentialIT.hasRegexTimeout("The value 'x' does not match the regex"));
    }
}
