package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.terminology.TerminologySettings;

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
 * @param terminology the context's terminology setting (spec 007 FR-004): part of what the context is built from, so
 *                    a changed setting rebuilds it; {@link TerminologySettings#NONE} for the base context
 */
public record ResolvedContext(List<String> packages, Map<String, byte[]> hashes, List<String> missing,
                              TerminologySettings terminology) {
    public static final ResolvedContext EMPTY = new ResolvedContext(List.of(), Map.of());

    public ResolvedContext {
        packages = List.copyOf(packages);
        hashes = Map.copyOf(hashes);
        missing = List.copyOf(missing);
        terminology = terminology == null ? TerminologySettings.NONE : terminology;
    }

    public ResolvedContext(List<String> packages, Map<String, byte[]> hashes, List<String> missing) {
        this(packages, hashes, missing, TerminologySettings.NONE);
    }

    public ResolvedContext(List<String> packages, Map<String, byte[]> hashes) {
        this(packages, hashes, List.of());
    }

    /** The same packages with terminology setting {@code settings}. */
    public ResolvedContext withTerminology(TerminologySettings settings) {
        return new ResolvedContext(packages, hashes, missing, settings);
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
     * The context of an IG package resolved as the package's owner (spec 006 FR-024).
     *
     * @param igPackage a concrete {@code name#version}, or {@code null} for the base context
     */
    public static ResolvedContext of(String igPackage, PackageCatalogView view) {
        if (igPackage == null) {
            return EMPTY;
        }
        Access root = view.access(igPackage);
        return of(igPackage, view, root == null ? null : root.owner());
    }

    /**
     * Resolves the context of one IG package against the catalog with the deterministic {@link DependencyResolver}.
     * A dependency declared without an explicit version is chosen among the versions {@code owner} may select
     * ({@link AccessPolicy#selectableFor}), so another organisation's private version is never chosen. A root package
     * that is not stored stays in the list, so building it fails naming that package instead of silently producing a
     * core-only context.
     *
     * @param igPackage a concrete {@code name#version}, or {@code null} for the base context
     * @param owner     the organisation the context is resolved for: the included context's owner, or the IG
     *                  package's owner for an on-demand context
     */
    public static ResolvedContext of(String igPackage, PackageCatalogView view, UUID owner) {
        if (igPackage == null) {
            return EMPTY;
        }
        DependencyResolver.Resolution resolution = DependencyResolver.resolve(Set.of(igPackage), view.asCatalog(),
                p -> AccessPolicy.selectableFor(owner, view.access(p)));
        List<String> resolved = resolution.resolved();
        if (!resolved.contains(igPackage)) {
            resolved = new java.util.ArrayList<>(resolved);
            resolved.add(igPackage);
            resolved.sort(null);
        }
        Map<String, byte[]> hashes = new HashMap<>();
        for (String p : resolved) {
            byte[] sha = view.sha256(p);
            if (sha != null) {
                hashes.put(p, sha);
            }
        }
        List<String> missing = resolution.missing().stream().filter(m -> !m.equals(igPackage)).toList();
        return new ResolvedContext(resolved, hashes, missing);
    }

    /**
     * Same packages with the same content (hashes compared by value) and the same terminology setting: a context
     * whose setting changed must be rebuilt (spec 007 FR-004, research R12).
     */
    public boolean sameAs(ResolvedContext other) {
        if (other == null || !packages.equals(other.packages) || !hashes.keySet().equals(other.hashes.keySet())
                || !terminology.equals(other.terminology)) {
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
