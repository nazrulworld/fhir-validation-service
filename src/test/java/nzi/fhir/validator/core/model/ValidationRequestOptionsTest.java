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
}
