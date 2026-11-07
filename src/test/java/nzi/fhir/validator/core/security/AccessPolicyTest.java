package nzi.fhir.validator.core.security;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.AccessPolicy.Decision;
import nzi.fhir.validator.core.validator.PinnedAccess;
import nzi.fhir.validator.core.validator.SnapshotKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The access rule (spec 005 FR-010, FR-011): the full caller × sharing matrix, and the context rule for base, pinned
 * and on-demand keys and for dependencies.
 */
class AccessPolicyTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Caller OWNER = Caller.of(A, "a", ApiKeyRepository.Role.VALIDATE);
    private static final Caller OTHER = Caller.of(B, "b", ApiKeyRepository.Role.ADMIN);
    private static final Caller SYSTEM = Caller.of(Organisations.SYSTEM_ID, "system", ApiKeyRepository.Role.VALIDATE);
    private static final Caller ANON = Caller.ANONYMOUS;

    private static final Access A_PRIVATE = new Access(A, "a", false);
    private static final Access A_SHARED = new Access(A, "a", true);
    private static final Access B_PRIVATE = new Access(B, "b", false);
    private static final Access B_SHARED = new Access(B, "b", true);

    @Test
    void canUseMatrix() {
        // shareable: everyone
        for (Caller c : List.of(OWNER, OTHER, SYSTEM, ANON)) {
            assertTrue(AccessPolicy.canUse(c, A_SHARED), c.label() + " on shareable");
        }
        // private: owner and system only
        assertTrue(AccessPolicy.canUse(OWNER, A_PRIVATE));
        assertTrue(AccessPolicy.canUse(SYSTEM, A_PRIVATE));
        assertFalse(AccessPolicy.canUse(OTHER, A_PRIVATE));
        assertFalse(AccessPolicy.canUse(ANON, A_PRIVATE));
        // not stored: nobody
        for (Caller c : List.of(OWNER, OTHER, SYSTEM, ANON)) {
            assertFalse(AccessPolicy.canUse(c, null));
        }
    }

    @Test
    void systemKeyOfAnyRoleIsSystem() {
        assertTrue(SYSTEM.system());
        assertTrue(Caller.system("system").system());
        assertFalse(OWNER.system());
        assertFalse(ANON.system());
        assertFalse(ANON.owns(null), "anonymous owns nothing, not even rows with a null owner");
    }

    private static PackageCatalogView view(Map<String, Access> packages, Map<String, List<String>> deps) {
        java.util.Map<String, PackageCatalogView.Entry> entries = new java.util.HashMap<>();
        packages.forEach((k, a) -> entries.put(k, new PackageCatalogView.Entry(Set.of(), deps.getOrDefault(k, List.of()),
                null, 0, a)));
        return new PackageCatalogView(1, entries);
    }

    private static final SnapshotKey IG = SnapshotKey.of(SupportedFhirVersion.R4, "ig.a#1.0.0");

    @Test
    void baseContextIsAlwaysAllowed() {
        PackageCatalogView view = view(Map.of(), Map.of());
        for (Caller c : List.of(OWNER, OTHER, SYSTEM, ANON)) {
            assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(c, SnapshotKey.base(SupportedFhirVersion.R4), List.of(),
                    view, PinnedAccess.EMPTY));
        }
    }

    @Test
    void onDemandKeyFollowsThePackage() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", A_PRIVATE), Map.of());
        List<String> resolved = List.of("ig.a#1.0.0");
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(OWNER, IG, resolved, view, PinnedAccess.EMPTY));
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(SYSTEM, IG, resolved, view, PinnedAccess.EMPTY));
        assertEquals(Decision.IG_NOT_VISIBLE, AccessPolicy.canUseContext(OTHER, IG, resolved, view, PinnedAccess.EMPTY));
        assertEquals(Decision.IG_NOT_VISIBLE, AccessPolicy.canUseContext(ANON, IG, resolved, view, PinnedAccess.EMPTY));
    }

    @Test
    void pinnedKeyUsesItsOwnAccessWhenItDiffersFromThePackage() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", A_SHARED), Map.of());
        List<String> resolved = List.of("ig.a#1.0.0");
        // Shareable package, but the included context is private to A.
        PinnedAccess pinned = PinnedAccess.of(List.of("ig.a#1.0.0"), Map.of("ig.a#1.0.0", A_PRIVATE), "system");
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(OWNER, IG, resolved, view, pinned));
        assertEquals(Decision.IG_NOT_VISIBLE, AccessPolicy.canUseContext(OTHER, IG, resolved, view, pinned));
        assertEquals(Decision.IG_NOT_VISIBLE, AccessPolicy.canUseContext(ANON, IG, resolved, view, pinned));
        // A shareable context over a package that is private to A: the package still decides (FR-010).
        PackageCatalogView privatePackage = view(Map.of("ig.a#1.0.0", A_PRIVATE), Map.of());
        PinnedAccess shared = PinnedAccess.of(List.of("ig.a#1.0.0"), Map.of("ig.a#1.0.0", B_SHARED), "system");
        assertEquals(Decision.IG_NOT_VISIBLE, AccessPolicy.canUseContext(OTHER, IG, resolved, privatePackage, shared));
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(OWNER, IG, resolved, privatePackage, shared));
    }

    @Test
    void pinnedKeyWithoutARowIsSystemOwnedAndShareable() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", B_SHARED), Map.of());
        PinnedAccess pinned = PinnedAccess.of(List.of("ig.a#1.0.0"), Map.of(), "system");
        assertTrue(pinned.isPinned("ig.a#1.0.0"));
        assertEquals(Organisations.SYSTEM_ID, pinned.get("ig.a#1.0.0").owner());
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(ANON, IG, List.of("ig.a#1.0.0"), view, pinned));
    }

    @Test
    void shareableIgWithAPrivateDependency() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", A_SHARED, "dep.b#2.0.0", B_PRIVATE),
                Map.of("ig.a#1.0.0", List.of("dep.b#2.0.0")));
        List<String> resolved = List.of("dep.b#2.0.0", "ig.a#1.0.0");
        assertEquals(Decision.DEPENDENCY_NOT_VISIBLE, AccessPolicy.canUseContext(OWNER, IG, resolved, view, PinnedAccess.EMPTY),
                "the IG's owner doesn't own the dependency");
        assertEquals(Decision.DEPENDENCY_NOT_VISIBLE, AccessPolicy.canUseContext(ANON, IG, resolved, view, PinnedAccess.EMPTY));
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(OTHER, IG, resolved, view, PinnedAccess.EMPTY),
                "the dependency's owner");
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(SYSTEM, IG, resolved, view, PinnedAccess.EMPTY));
    }

    @Test
    void aPackageNoLongerStoredIsSkippedAsADependency() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", A_SHARED), Map.of());
        assertEquals(Decision.ALLOWED, AccessPolicy.canUseContext(OTHER, IG, List.of("gone#1.0.0", "ig.a#1.0.0"), view,
                PinnedAccess.EMPTY));
    }

    @Test
    void publicMeansEverythingShareable() {
        PackageCatalogView view = view(Map.of("ig.a#1.0.0", A_SHARED, "dep.b#2.0.0", B_PRIVATE), Map.of());
        assertTrue(AccessPolicy.isPublic(SnapshotKey.base(SupportedFhirVersion.R4), List.of(), view, PinnedAccess.EMPTY));
        assertTrue(AccessPolicy.isPublic(IG, List.of("ig.a#1.0.0"), view, PinnedAccess.EMPTY));
        assertFalse(AccessPolicy.isPublic(IG, List.of("dep.b#2.0.0", "ig.a#1.0.0"), view, PinnedAccess.EMPTY));
        assertFalse(AccessPolicy.isPublic(IG, List.of("ig.a#1.0.0"), view,
                PinnedAccess.of(List.of("ig.a#1.0.0"), Map.of("ig.a#1.0.0", A_PRIVATE), "system")));
    }

    @Test
    void highestVisibleVersion() {
        PackageCatalogView view = view(Map.of("p#1.0.0", B_SHARED, "p#2.0.0", A_PRIVATE), Map.of());
        assertEquals("2.0.0", view.highestVisible("p", OWNER).orElseThrow());
        assertEquals("2.0.0", view.highestVisible("p", SYSTEM).orElseThrow());
        assertEquals("1.0.0", view.highestVisible("p", OTHER).orElseThrow());
        assertEquals("1.0.0", view.highestVisible("p", ANON).orElseThrow());
        assertTrue(view(Map.of("p#2.0.0", A_PRIVATE), Map.of()).highestVisible("p", OTHER).isEmpty());
    }
}
