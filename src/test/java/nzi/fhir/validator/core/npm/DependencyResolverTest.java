package nzi.fhir.validator.core.npm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    /** name#version -> declared dependencies */
    private static DependencyResolver.Catalog catalog(Map<String, List<String>> packages) {
        return DependencyResolver.Catalog.of(packages);
    }

    @Test
    void explicitVersionsAreUsedExactly() {
        var cat = catalog(Map.of(
                "a#1.0.0", List.of("b#2.0.0"),
                "b#1.0.0", List.of(),
                "b#2.0.0", List.of()));
        var r = DependencyResolver.resolve(List.of("a#1.0.0"), cat);
        assertEquals(List.of("a#1.0.0", "b#2.0.0"), r.resolved());
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
    }

    @Test
    void missingCurrentOrLatestResolveToHighestStoredRelease() {
        var cat = catalog(Map.of(
                "a#1.0.0", List.of("b", "c#current", "d#latest"),
                "b#1.0.0", List.of(), "b#1.2.0", List.of(), "b#2.0.0-ballot", List.of(),
                "c#0.9.0", List.of(), "c#0.10.0", List.of(),
                "d#3.0.0", List.of()));
        var r = DependencyResolver.resolve(List.of("a#1.0.0"), cat);
        assertEquals(List.of("a#1.0.0", "b#1.2.0", "c#0.10.0", "d#3.0.0"), r.resolved());
    }

    @Test
    void diamondKeepsHighestVersionAndWarns() {
        var cat = catalog(Map.of(
                "root#1.0.0", List.of("x#1.0.0", "y#1.0.0"),
                "x#1.0.0", List.of("a#1.0.0"),
                "y#1.0.0", List.of("a#1.2.0"),
                "a#1.0.0", List.of("onlyInOld#1.0.0"),
                "a#1.2.0", List.of(),
                "onlyInOld#1.0.0", List.of()));
        var r = DependencyResolver.resolve(List.of("root#1.0.0"), cat);
        assertEquals(List.of("a#1.2.0", "root#1.0.0", "x#1.0.0", "y#1.0.0"), r.resolved(),
                "dependencies of the discarded a#1.0.0 must not leak into the result");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("a") && w.contains("1.0.0") && w.contains("1.2.0")), r.warnings().toString());
    }

    @Test
    void cyclesTerminate() {
        var cat = catalog(Map.of(
                "a#1.0.0", List.of("b#1.0.0"),
                "b#1.0.0", List.of("a#1.0.0")));
        var r = DependencyResolver.resolve(List.of("a#1.0.0"), cat);
        assertEquals(List.of("a#1.0.0", "b#1.0.0"), r.resolved());
    }

    @Test
    void corePackagesAreSkipped() {
        var cat = catalog(Map.of("a#1.0.0", List.of("hl7.fhir.r4.core#4.0.1", "hl7.fhir.r5.core#5.0.0")));
        var r = DependencyResolver.resolve(List.of("a#1.0.0"), cat);
        assertEquals(List.of("a#1.0.0"), r.resolved());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void missingDependencyIsReportedNotResolved() {
        var cat = catalog(Map.of("a#1.0.0", List.of("ghost#1.0.0", "phantom")));
        var r = DependencyResolver.resolve(List.of("a#1.0.0"), cat);
        assertEquals(List.of("a#1.0.0"), r.resolved());
        assertEquals(2, r.warnings().size(), r.warnings().toString());
    }

    @Test
    void resultIsIndependentOfInputAndCatalogOrder() {
        Map<String, List<String>> packages = Map.ofEntries(
                Map.entry("r1#1.0.0", List.of("x#1.0.0", "y#1.0.0", "z")),
                Map.entry("r2#2.0.0", List.of("x#1.1.0", "w#1.0.0")),
                Map.entry("x#1.0.0", List.of("q#1.0.0")), Map.entry("x#1.1.0", List.of("q#2.0.0")),
                Map.entry("y#1.0.0", List.of("w#2.0.0")),
                Map.entry("z#1.0.0", List.of()), Map.entry("z#0.5.0", List.of()),
                Map.entry("w#1.0.0", List.of()), Map.entry("w#2.0.0", List.of()),
                Map.entry("q#1.0.0", List.of()), Map.entry("q#2.0.0", List.of()));
        var expected = DependencyResolver.resolve(List.of("r1#1.0.0", "r2#2.0.0"), catalog(packages));
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            List<String> roots = new ArrayList<>(List.of("r1#1.0.0", "r2#2.0.0"));
            Collections.shuffle(roots, random);
            var r = DependencyResolver.resolve(roots, catalog(packages));
            assertEquals(expected.resolved(), r.resolved());
            assertEquals(expected.warnings(), r.warnings());
        }
        assertEquals(List.of("q#2.0.0", "r1#1.0.0", "r2#2.0.0", "w#2.0.0", "x#1.1.0", "y#1.0.0", "z#1.0.0"), expected.resolved());
    }
}
