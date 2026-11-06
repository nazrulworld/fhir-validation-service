package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.PackageCatalogView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The packages one validator context is built from, with their content hashes (spec 004, research R3).
 *
 * @param packages sorted {@code name#version} list; empty for the base context
 * @param hashes   content hash per package, where one is recorded
 * @param missing  required dependencies that are not stored (skipped from {@code packages}); an on-demand context
 *                 with missing dependencies is refused with 424 (FR-017), an included one builds without them, with
 *                 a warning, as include always has
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
     * Resolves the context of one IG package against the catalog with the deterministic {@link DependencyResolver}.
     * A root package that is not stored stays in the list, so building it fails naming that package instead of
     * silently producing a core-only context.
     *
     * @param igPackage a concrete {@code name#version}, or {@code null} for the base context
     */
    public static ResolvedContext of(String igPackage, PackageCatalogView view) {
        if (igPackage == null) {
            return EMPTY;
        }
        DependencyResolver.Resolution resolution = DependencyResolver.resolve(Set.of(igPackage), view.asCatalog());
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

    /** Same packages with the same content (hashes compared by value). */
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
