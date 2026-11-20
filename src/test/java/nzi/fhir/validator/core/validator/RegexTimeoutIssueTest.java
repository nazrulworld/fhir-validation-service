package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirContextLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 013 FR-001, research R2: recognising an issue that reports a regex check stopped by the engine's time limit. */
class RegexTimeoutIssueTest {

    static SingleValidationMessage message(String id, String text, ResultSeverityEnum severity) {
        SingleValidationMessage m = new SingleValidationMessage();
        m.setMessageId(id);
        m.setMessage(text);
        m.setSeverity(severity);
        return m;
    }

    @Test
    void theRegexMatchTimedOutIdIsATimeoutInAnyLanguage() {
        assertTrue(RegexTimeoutIssue.is(message("REGEX_MATCH_TIMED_OUT", "The regex '\\S*' could not be executed",
                ResultSeverityEnum.ERROR)));
        assertTrue(RegexTimeoutIssue.is(message("REGEX_MATCH_TIMED_OUT", "Der reguläre Ausdruck '\\S*' konnte nicht ausgeführt werden",
                ResultSeverityEnum.ERROR)), "recognised by its id, not its text");
    }

    @Test
    void aPrimitiveRegexExceptionIsATimeoutOnlyWithTheTimeoutText() {
        assertTrue(RegexTimeoutIssue.is(message("TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION",
                "Exception evaluating regex '\\S*' on type uri: Regex evaluation timed out after 500ms", ResultSeverityEnum.ERROR)));
        assertFalse(RegexTimeoutIssue.is(message("TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION",
                "Exception evaluating regex '[' on type uri: Unclosed character class", ResultSeverityEnum.ERROR)));
    }

    @Test
    void aConstraintWhoseRegexTimedOutIsATimeoutWhateverItsId() {
        assertTrue(RegexTimeoutIssue.is(message("http://example.org/fhir/StructureDefinition/regex-trap-patient#trap-1",
                "Constraint failed: trap-1: 'family matches a runaway regex' (defined in "
                        + "http://example.org/fhir/StructureDefinition/regex-trap-patient)Timeout evaluating regex: ^(.*?,){20}P$",
                ResultSeverityEnum.ERROR)));
    }

    @Test
    void otherIssuesAreNotTimeouts() {
        assertFalse(RegexTimeoutIssue.is(message(null, "Patient.birthDate: minimum required = 1", ResultSeverityEnum.ERROR)));
        assertFalse(RegexTimeoutIssue.is(message("Type_Specific_Checks_DT_Primitive_Regex",
                "Element value 'a b' does not meet regex '\\S*'", ResultSeverityEnum.ERROR)));
        assertFalse(RegexTimeoutIssue.is(message(null, null, ResultSeverityEnum.INFORMATION)));
    }

    @Test
    void severityIsNeverUsed() {
        assertTrue(RegexTimeoutIssue.is(message("REGEX_MATCH_TIMED_OUT", "x", ResultSeverityEnum.WARNING)));
        assertTrue(RegexTimeoutIssue.is(message("REGEX_MATCH_TIMED_OUT", "x", ResultSeverityEnum.INFORMATION)));
    }

    @Test
    void countCountsEveryTimeoutInAResult() {
        ValidationResult result = new ValidationResult(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4), List.of(
                message("REGEX_MATCH_TIMED_OUT", "a", ResultSeverityEnum.ERROR),
                message(null, "Patient.birthDate: minimum required = 1", ResultSeverityEnum.ERROR),
                message("x#c1", "Constraint failed: c1Timeout evaluating regex: (a)", ResultSeverityEnum.ERROR)));
        assertEquals(2, RegexTimeoutIssue.count(result));
        assertEquals(0, RegexTimeoutIssue.count(new ValidationResult(
                FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4), List.of())));
    }
}
