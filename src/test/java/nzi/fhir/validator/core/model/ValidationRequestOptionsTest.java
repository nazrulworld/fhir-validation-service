package nzi.fhir.validator.core.model;

import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-006a and the spec's list edge cases: the {@code profile} query value is normalised (trimmed, blanks dropped,
 * de-duplicated in first-occurrence order) and then bounded to 10 entries of at most 1024 characters.
 */
class ValidationRequestOptionsTest {

    private static String urls(int count) {
        return IntStream.range(0, count).mapToObj(i -> "http://example.org/StructureDefinition/p" + i)
                .collect(Collectors.joining(","));
    }

    @Test
    void absentOrBlankGivesEmptyList() {
        assertEquals(List.of(), ValidationRequestOptions.parseProfiles(null));
        assertEquals(List.of(), ValidationRequestOptions.parseProfiles(""));
        assertEquals(List.of(), ValidationRequestOptions.parseProfiles(" , ,"));
    }

    @Test
    void entriesAreTrimmedBlanksDroppedAndDuplicatesRemovedInOrder() {
        assertEquals(List.of("a", "b"), ValidationRequestOptions.parseProfiles(" a,,b ,a"));
    }

    @Test
    void tenDistinctProfilesAreAccepted() {
        assertEquals(10, ValidationRequestOptions.parseProfiles(urls(10)).size());
    }

    @Test
    void limitIsAppliedAfterDeduplication() {
        String same = String.join(",", java.util.Collections.nCopies(20, "http://example.org/StructureDefinition/x"));
        assertEquals(List.of("http://example.org/StructureDefinition/x"), ValidationRequestOptions.parseProfiles(same));
    }

    @Test
    void elevenDistinctProfilesAreRejected() {
        ProblemException e = assertThrows(ProblemException.class, () -> ValidationRequestOptions.parseProfiles(urls(11)));
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertFalse(e.extensions().containsKey("unresolvedProfiles"), "limit errors do not echo URLs");
    }

    @Test
    void urlOf1024CharactersIsAcceptedAnd1025Rejected() {
        String prefix = "http://example.org/";
        String ok = prefix + "a".repeat(1024 - prefix.length());
        assertEquals(1024, ok.length());
        assertEquals(List.of(ok), ValidationRequestOptions.parseProfiles(ok));
        ProblemException e = assertThrows(ProblemException.class, () -> ValidationRequestOptions.parseProfiles(ok + "a"));
        assertEquals("invalid-request", e.type());
    }

    @Test
    void resultIsImmutable() {
        List<String> parsed = ValidationRequestOptions.parseProfiles("a");
        assertThrows(UnsupportedOperationException.class, () -> parsed.add("b"));
    }

    // ---- Spec 010 FR-005, FR-007, contracts §2: tx and txMode

    private static nzi.fhir.validator.core.terminology.TerminologyOverride tx(List<String> tx, List<String> mode) {
        return ValidationRequestOptions.parseTerminology(tx, mode);
    }

    private static void refused(List<String> tx, List<String> mode, String detail) {
        ProblemException e = assertThrows(ProblemException.class, () -> tx(tx, mode));
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertEquals(detail, e.detail());
        for (String value : tx == null ? List.<String>of() : tx) {
            if (value.length() > 8) {
                assertFalse(e.detail().contains(value.trim()), "the address is never echoed");
            }
        }
    }

    @Test
    void absentParametersGiveNoOverride() {
        assertFalse(tx(List.of(), List.of()).present());
        assertFalse(tx(null, null).present());
    }

    @Test
    void txIsTrimmedAndNormalised() {
        var o = tx(List.of("  HTTPS://Tx.Example.org/r4/ "), List.of());
        assertEquals("https://tx.example.org/r4", o.server());
        assertNull(o.mode());
        assertEquals(nzi.fhir.validator.core.terminology.TerminologyMode.ECOSYSTEM,
                tx(List.of(), List.of("ecosystem")).mode());
    }

    @Test
    void everyMalformedInputHasItsFixedText() {
        refused(List.of("https://a.example.org", "https://b.example.org"), List.of(), "Give tx and txMode at most once each");
        refused(List.of(), List.of("none", "server"), "Give tx and txMode at most once each");
        refused(List.of("  "), List.of(), "tx and txMode must not be empty");
        refused(List.of(), List.of(""), "tx and txMode must not be empty");
        refused(List.of(), List.of("Server"), "txMode must be none, server or ecosystem");
        refused(List.of(), List.of("remote"), "txMode must be none, server or ecosystem");
        refused(List.of("ftp://tx.example.org/fhir"), List.of(), "tx must be an http or https base URL without query, fragment or user info");
        refused(List.of("https://tx.example.org/fhir?x=1"), List.of(), "tx must be an http or https base URL without query, fragment or user info");
        refused(List.of("https://user@tx.example.org/fhir"), List.of(), "tx must be an http or https base URL without query, fragment or user info");
        refused(List.of("https://tx.example.org/" + "a".repeat(500)), List.of(), "tx must be at most 512 characters");
        refused(List.of("https://tx.example.org/fhir"), List.of("none"), "tx is not allowed with txMode=none");
    }

    @Test
    void txOf512CharactersIsAccepted() {
        String prefix = "https://tx.example.org/";
        String ok = prefix + "a".repeat(512 - prefix.length());
        assertEquals(ok, tx(List.of(ok), List.of()).server());
    }
}
