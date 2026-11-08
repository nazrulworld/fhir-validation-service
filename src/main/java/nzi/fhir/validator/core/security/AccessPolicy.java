package nzi.fhir.validator.core.security;

import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.validator.PinnedAccess;
import nzi.fhir.validator.core.validator.SnapshotKey;

import java.util.Collection;

/**
 * The access rule (spec 005 FR-010, FR-011, research R4, data-model §2). Pure and allocation-free apart from
 * iteration, so it can run on the validation hot path with no database access (FR-013).
 * <pre>
 * canUse(caller, a)        = a.shareable || caller.system || (!caller.anonymous &amp;&amp; caller.org == a.owner)
 * snapshotAccess(k)        = pinnedAccess[k] if k is pinned, the package's own access otherwise (on-demand)
 * allowed(caller, k)       = k is base
 *                            || (canUse(caller, snapshotAccess(k)) &amp;&amp; ∀ p ∈ resolved(k): canUse(caller, access(p)))
 * </pre>
 */
public final class AccessPolicy {

    public enum Decision {
        ALLOWED,
        /** The named IG package, or its context, isn't usable: answered exactly like "not stored" (404). */
        IG_NOT_VISIBLE,
        /** A dependency isn't usable: answered 424 without naming it. */
        DEPENDENCY_NOT_VISIBLE
    }

    private AccessPolicy() {
    }

    /** Whether {@code caller} may use content with access {@code a}; {@code null} (not stored) is never usable. */
    public static boolean canUse(Caller caller, Access a) {
        if (a == null) {
            return false;
        }
        return a.shareable() || caller.system() || caller.owns(a.owner());
    }

    /**
     * Whether a stored package version with access {@code a} may be chosen for a dependency of a context owned by
     * {@code owner} that is declared without an explicit version (spec 006 FR-024): it is shareable or owned by
     * {@code owner}. Unlike {@link #canUse}, the system organisation gets no exception, so a shareable system-owned
     * context never resolves to a version other organisations can't use. Depends only on shared state, so every
     * instance chooses the same version.
     */
    public static boolean selectableFor(java.util.UUID owner, Access a) {
        return a != null && (a.shareable() || (owner != null && owner.equals(a.owner())));
    }

    /**
     * Whether {@code caller} may validate against the context {@code key}.
     *
     * @param resolvedPackages the context's packages ({@code name#version}, the named IG included); packages no longer
     *                         stored are skipped, a missing dependency is reported by the build, not here
     */
    public static Decision canUseContext(Caller caller, SnapshotKey key, Collection<String> resolvedPackages,
                                         PackageCatalogView view, PinnedAccess pinned) {
        if (key.isBase() || caller.system()) {
            return Decision.ALLOWED;
        }
        String ig = key.igPackage();
        Access context = pinned == null ? null : pinned.get(ig);
        if (context == null) {
            context = view.access(ig);
        }
        if (!canUse(caller, context) || !canUse(caller, view.access(ig))) {
            return Decision.IG_NOT_VISIBLE;
        }
        for (String p : resolvedPackages) {
            if (p.equals(ig)) {
                continue;
            }
            Access a = view.access(p);
            if (a != null && !canUse(caller, a)) {
                return Decision.DEPENDENCY_NOT_VISIBLE;
            }
        }
        return Decision.ALLOWED;
    }

    /**
     * Whether a context may be listed on the unauthenticated health endpoint (research R12): its own access and every
     * package's access are shareable.
     */
    public static boolean isPublic(SnapshotKey key, Collection<String> resolvedPackages, PackageCatalogView view,
                                   PinnedAccess pinned) {
        if (key.isBase()) {
            return true;
        }
        Access context = pinned == null ? null : pinned.get(key.igPackage());
        if (context == null) {
            context = view.access(key.igPackage());
        }
        if (context != null && !context.shareable()) {
            return false;
        }
        for (String p : resolvedPackages) {
            Access a = view.access(p);
            if (a != null && !a.shareable()) {
                return false;
            }
        }
        return true;
    }
}
