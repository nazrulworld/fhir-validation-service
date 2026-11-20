package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.org.Organisations;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Packages of a two-IG context (spec 012 FR-004, research R6): both members are roots of one deterministic resolution.
 */
class ResolvedContextTest {
    private static final Access SHARED = new Access(Organisations.SYSTEM_ID, "system", true);

    private static PackageCatalogView view(Map<String, List<String>> packages) {
        Map<String, PackageCatalogView.Entry> entries = new HashMap<>();
        packages.forEach((k, deps) -> entries.put(k, new PackageCatalogView.Entry(Set.of(), deps, null, 0, SHARED)));
        return new PackageCatalogView(1, entries);
    }

    @Test
    void bothMembersAndTheirDependencies() {
        PackageCatalogView view = view(Map.of(
                "a#1.0.0", List.of("x#1.0.0"),
                "b#2.0.0", List.of("y#1.0.0"),
                "x#1.0.0", List.of(),
                "y#1.0.0", List.of()));
        ResolvedContext ctx = ResolvedContext.of("a#1.0.0|b#2.0.0", view, Organisations.SYSTEM_ID);
        assertEquals(List.of("a#1.0.0", "b#2.0.0", "x#1.0.0", "y#1.0.0"), ctx.packages());
        assertEquals(List.of(), ctx.missing());
        assertEquals(ctx.packages(), ResolvedContext.of("b#2.0.0|a#1.0.0", view, Organisations.SYSTEM_ID).packages(),
                "the package set doesn't depend on member order");
    }

    @Test
    void sharedPackageAppearsOnce() {
        PackageCatalogView view = view(Map.of(
                "a#1.0.0", List.of("x#1.0.0"),
                "b#2.0.0", List.of("x#1.0.0"),
                "x#1.0.0", List.of()));
        assertEquals(List.of("a#1.0.0", "b#2.0.0", "x#1.0.0"),
                ResolvedContext.of("a#1.0.0|b#2.0.0", view, Organisations.SYSTEM_ID).packages());
    }

    @Test
    void aMemberThatIsNotStoredStaysSoTheBuildNamesIt() {
        PackageCatalogView view = view(Map.of("a#1.0.0", List.of()));
        ResolvedContext ctx = ResolvedContext.of("a#1.0.0|gone#9.0.0", view, Organisations.SYSTEM_ID);
        assertTrue(ctx.packages().contains("gone#9.0.0"));
        assertTrue(ctx.packages().contains("a#1.0.0"));
        assertFalse(ctx.missing().contains("gone#9.0.0"), "a member is not reported as a missing dependency");
    }

    @Test
    void singleKeyUnchanged() {
        PackageCatalogView view = view(Map.of("a#1.0.0", List.of("x#1.0.0"), "x#1.0.0", List.of()));
        assertEquals(List.of("a#1.0.0", "x#1.0.0"), ResolvedContext.of("a#1.0.0", view, Organisations.SYSTEM_ID).packages());
    }
}
