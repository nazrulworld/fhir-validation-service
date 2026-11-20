package nzi.fhir.validator.core.npm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Include-time version conflicts between the two IGs of a context (spec 012 FR-006, FR-006a, research R4).
 */
class DependencyConflictTest {

    private static DependencyResolver.Catalog catalog(Map<String, List<String>> packages) {
        return DependencyResolver.Catalog.of(packages);
    }

    @Test
    void differentVersionsOfAPlainPackageConflict() {
        var cat = catalog(Map.of(
                "p#1.0.0", List.of("x#1.0.0"),
                "q#1.0.0", List.of("x#2.0.0"),
                "x#1.0.0", List.of(),
                "x#2.0.0", List.of()));
        List<DependencyResolver.Conflict> conflicts = DependencyResolver.conflicts("p#1.0.0", "q#1.0.0", cat, p -> true);
        assertEquals(1, conflicts.size());
        DependencyResolver.Conflict c = conflicts.get(0);
        assertEquals("x", c.packageName());
        assertEquals(Map.of("p#1.0.0", "1.0.0", "q#1.0.0", "2.0.0"), c.versions());
        assertFalse(c.exempt());
        assertEquals("2.0.0", c.highest());
    }

    @Test
    void baseHl7PackagesAreExempt() {
        var cat = catalog(Map.of(
                "t1#1.0.0", List.of("hl7.terminology.r4#5.0.0", "hl7.fhir.uv.extensions.r4#1.0.0"),
                "t2#1.0.0", List.of("hl7.terminology.r4#6.0.2", "hl7.fhir.uv.extensions.r4#5.1.0"),
                "hl7.terminology.r4#5.0.0", List.of(),
                "hl7.terminology.r4#6.0.2", List.of(),
                "hl7.fhir.uv.extensions.r4#1.0.0", List.of(),
                "hl7.fhir.uv.extensions.r4#5.1.0", List.of()));
        List<DependencyResolver.Conflict> conflicts = DependencyResolver.conflicts("t1#1.0.0", "t2#1.0.0", cat, p -> true);
        assertEquals(List.of("hl7.fhir.uv.extensions.r4", "hl7.terminology.r4"),
                conflicts.stream().map(DependencyResolver.Conflict::packageName).toList(), "sorted by package name");
        assertTrue(conflicts.stream().allMatch(DependencyResolver.Conflict::exempt));
        assertEquals("6.0.2", conflicts.get(1).highest());
    }

    @Test
    void aRootFoundInTheOtherTreeInAnotherVersionConflicts() {
        var cat = catalog(Map.of(
                "a#1.0.0", List.of(),
                "a#2.0.0", List.of(),
                "b#1.0.0", List.of("a#2.0.0")));
        List<DependencyResolver.Conflict> conflicts = DependencyResolver.conflicts("a#1.0.0", "b#1.0.0", cat, p -> true);
        assertEquals(1, conflicts.size());
        assertEquals("a", conflicts.get(0).packageName());
        assertEquals(Map.of("a#1.0.0", "1.0.0", "b#1.0.0", "2.0.0"), conflicts.get(0).versions());
    }

    @Test
    void oneIgIsADependencyOfTheOtherAtTheSameVersion() {
        var cat = catalog(Map.of(
                "a#1.0.0", List.of(),
                "b#1.0.0", List.of("a#1.0.0")));
        assertEquals(List.of(), DependencyResolver.conflicts("a#1.0.0", "b#1.0.0", cat, p -> true));
    }

    @Test
    void sameVersionsAreNoConflict() {
        var cat = catalog(Map.of(
                "p#1.0.0", List.of("x#1.0.0"),
                "q#1.0.0", List.of("x#1.0.0"),
                "x#1.0.0", List.of()));
        assertEquals(List.of(), DependencyResolver.conflicts("p#1.0.0", "q#1.0.0", cat, p -> true));
    }

    @Test
    void deterministicWhateverTheRootOrder() {
        var cat = catalog(Map.of(
                "p#1.0.0", List.of("x#1.0.0", "y#1.0.0"),
                "q#1.0.0", List.of("y#2.0.0", "x#2.0.0"),
                "x#1.0.0", List.of(), "x#2.0.0", List.of(), "y#1.0.0", List.of(), "y#2.0.0", List.of()));
        List<DependencyResolver.Conflict> one = DependencyResolver.conflicts("p#1.0.0", "q#1.0.0", cat, p -> true);
        List<DependencyResolver.Conflict> two = DependencyResolver.conflicts("q#1.0.0", "p#1.0.0", cat, p -> true);
        assertEquals(List.of("x", "y"), one.stream().map(DependencyResolver.Conflict::packageName).toList());
        assertEquals(one, two);
    }

    @Test
    void exemptPrefixes() {
        assertTrue(DependencyResolver.isConflictExempt("hl7.terminology"));
        assertTrue(DependencyResolver.isConflictExempt("hl7.terminology.r5"));
        assertTrue(DependencyResolver.isConflictExempt("hl7.fhir.uv.extensions.r4"));
        assertFalse(DependencyResolver.isConflictExempt("hl7.fhir.us.core"));
        assertFalse(DependencyResolver.isConflictExempt("hl7.terminologyx"), "a longer name is not under the prefix");
    }
}
