package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Recognises an issue that reports a regex check stopped by HL7 core's fixed 500 ms limit (spec 013 FR-001, research
 * R2). Under load that limit can fire for a cheap regex, so such an issue may say nothing about the resource.
 * <p>
 * Three forms, none depending on the message language: the message id {@code REGEX_MATCH_TIMED_OUT}; the id
 * {@code TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION} with the exception text built in HL7's
 * {@code RegexTimeout}; and the text the FHIRPath engine appends to a constraint whose {@code matches()} timed out
 * (the message id is then the constraint's own). The severity is never used: it is reported as the engine set it.
 */
public final class RegexTimeoutIssue {
    static final String MATCH_TIMED_OUT = "REGEX_MATCH_TIMED_OUT";
    static final String PRIMITIVE_REGEX_EXCEPTION = "TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION";
    /** Built in {@code org.hl7.fhir.utilities.regex.RegexTimeout}, never localised. */
    static final String ENGINE_TIMEOUT_TEXT = "Regex evaluation timed out after";
    /** Built in {@code org.hl7.fhir.r5.fhirpath.FHIRPathEngine}, never localised. */
    static final String FHIRPATH_TIMEOUT_TEXT = "Timeout evaluating regex: ";

    private RegexTimeoutIssue() {
    }

    public static boolean is(SingleValidationMessage message) {
        String id = message.getMessageId();
        String text = message.getMessage();
        if (MATCH_TIMED_OUT.equals(id)) {
            return true;
        }
        if (text == null) {
            return false;
        }
        return (PRIMITIVE_REGEX_EXCEPTION.equals(id) && text.contains(ENGINE_TIMEOUT_TEXT)) || text.contains(FHIRPATH_TIMEOUT_TEXT);
    }

    /** The number of regex-timeout issues in {@code result}. */
    public static int count(ValidationResult result) {
        int n = 0;
        for (SingleValidationMessage m : result.getMessages()) {
            if (is(m)) {
                n++;
            }
        }
        return n;
    }
}
