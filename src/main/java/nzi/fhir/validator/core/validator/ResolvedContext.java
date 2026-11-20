package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessPolicy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The packages one validator context is built from, with their content hashes (spec 004, research R3).
 *
 * @param packages sorted {@code name#version} list; empty for the base context
 * @param hashes   content hash per package, where one is recorded
 * @param missing  required dependencies that are not stored (skipped from {@code packages}); found at include time,
 *                 when the included context builds without them, with a warning, as include always has (validation
 *                 never builds a context since spec 007)
 * <p>
 * The terminology setting is not part of it (spec 010 FR-001): it is a per-request default, published by
 * {@code ConfigSync} as {@code TerminologyDefaults}, so a setting change never rebuilds a context.
 */
public record ResolvedContext(List<String> packages, Map<String, byte[]> hashes, List<String> missing) {
    public static final ResolvedContext EMPTY = new ResolvedContext(List.of(), Map.of());

    public ResolvedContext {
        packages = List.copyOf(packages);
        hashes = Map.copyOf(hashes);
        missing = List.copyOf(missing);
    }

    public ResolvedContext(List<String> packages, Map<String, byte[]> hashes) {
        this(packages, hashes, List.of());
    }

    /**
     * The context of {@code igPackage} as its build resolves it (spec 006 FR-024): an included context as its owner in
     * {@code pinned}. A key that isn't included (only possible in tests and in the moment an exclude is applied) is
     * resolved as the IG package's owner. Every place that needs a context's packages without its snapshot uses this,
     * so it matches the build.
     */
    public static ResolvedContext forKey(String igPackage, PackageCatalogView view, PinnedAccess pinned) {
        Access context = pinned == null || igPackage == null ? null : pinned.get(igPackage);
        return context == null ? of(igPackage, view) : of(igPackage, view, context.owner());
    }

    /**
     * The context of an IG package resolved as the package's owner (spec 006 FR-024). A two-IG key that isn't included
     * (only in tests) is resolved as its first IG's owner.
     *
     * @param igPackage a concrete {@code name#version}, a two-IG key, or {@code null} for the base context
     */
    public static ResolvedContext of(String igPackage, PackageCatalogView view) {
        if (igPackage == null) {
            return EMPTY;
        }
        Access root = view.access(SnapshotKey.membersOf(igPackage).get(0));
        return of(igPackage, view, root == null ? null : root.owner());
    }

    /**
     * Resolves the context of one IG package, or of the two IG packages of a two-IG key (spec 012 FR-004: both are
     * roots of one resolution), against the catalog with the deterministic {@link DependencyResolver}.
     * A dependency declared without an explicit version is chosen among the versions {@code owner} may select
     * ({@link AccessPolicy#selectableFor}), so another organisation's private version is never chosen. A root package
     * that is not stored stays in the list, so building it fails naming that package instead of silently producing a
     * core-only context.
     *
     * @param igPackage a concrete {@code name#version}, a two-IG key {@code A#a|B#b}, or {@code null} for the base
     *                  context
     * @param owner     the organisation the context is resolved for: the included context's owner, or the IG
     *                  package's owner for an on-demand context
     */
    public static ResolvedContext of(String igPackage, PackageCatalogView view, UUID owner) {
        if (igPackage == null) {
            return EMPTY;
        }
        List<String> members = SnapshotKey.membersOf(igPackage);
        DependencyResolver.Resolution resolution = DependencyResolver.resolve(Set.copyOf(members), view.asCatalog(),
                p -> AccessPolicy.selectableFor(owner, view.access(p)));
        List<String> resolved = resolution.resolved();
        if (!resolved.containsAll(members)) {
            resolved = new java.util.ArrayList<>(resolved);
            for (String m : members) {
                if (!resolved.contains(m)) {
                    resolved.add(m);
                }
            }
            resolved.sort(null);
        }
        Map<String, byte[]> hashes = new HashMap<>();
        for (String p : resolved) {
            byte[] sha = view.sha256(p);
            if (sha != null) {
                hashes.put(p, sha);
            }
        }
        List<String> missing = resolution.missing().stream().filter(m -> !members.contains(m)).toList();
        return new ResolvedContext(resolved, hashes, missing);
    }

    /**
     * Same packages with the same content (hashes compared by value). The terminology setting is not compared: a
     * setting change takes effect on the next request without a rebuild (spec 010 FR-001).
     */
    public boolean sameAs(ResolvedContext other) {
        if (other == null || !packages.equals(other.packages) || !hashes.keySet().equals(other.hashes.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : hashes.entrySet()) {
            if (!Arrays.equals(e.getValue(), other.hashes.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Packages whose content hash differs between this context and {@code view} (replaced in storage). */
    public List<String> replacedIn(PackageCatalogView view) {
        return hashes.entrySet().stream()
                .filter(e -> {
                    byte[] now = view.sha256(e.getKey());
                    return now != null && !Arrays.equals(now, e.getValue());
                })
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
