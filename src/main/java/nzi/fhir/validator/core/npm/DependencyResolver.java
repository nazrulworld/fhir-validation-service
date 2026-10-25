package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.core.enums.FhirCoreIgPackageType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic, version-aware IG dependency resolution (research R6):
 * <ol>
 *     <li>core packages ({@code hl7.fhir.rX.core}) are skipped;</li>
 *     <li>an explicit version is used exactly;</li>
 *     <li>a missing, {@code current} or {@code latest} version resolves to the highest stored release;</li>
 *     <li>if a package is reached with different versions, the highest wins (with a warning) and only the
 *     winner's dependencies are followed;</li>
 *     <li>cycles terminate.</li>
 * </ol>
 * The same inputs always produce the same sorted result, independent of input or catalog order.
 */
public final class DependencyResolver {

    public record Resolution(List<String> resolved, List<String> warnings) {
    }

    /**
     * Package metadata available for resolution (from storage).
     */
    public interface Catalog {
        /** Stored versions of a package (any order). */
        Collection<String> versions(String name);

        /** Declared dependencies ({@code name#version} or bare {@code name}) of a stored package. */
        List<String> dependencies(String name, String version);

        static Catalog of(Map<String, List<String>> packages) {
            Map<String, Set<String>> versions = new HashMap<>();
            Map<String, List<String>> deps = new HashMap<>();
            packages.forEach((idAndVersion, d) -> {
                String[] p = split(idAndVersion);
                versions.computeIfAbsent(p[0], k -> new HashSet<>()).add(p[1]);
                deps.put(p[0] + "#" + p[1], d == null ? List.of() : List.copyOf(d));
            });
            return new Catalog() {
                @Override
                public Collection<String> versions(String name) {
                    return versions.getOrDefault(name, Set.of());
                }

                @Override
                public List<String> dependencies(String name, String version) {
                    return deps.getOrDefault(name + "#" + version, List.of());
                }
            };
        }
    }

    private DependencyResolver() {
    }

    public static Resolution resolve(Collection<String> roots, Catalog catalog) {
        Set<String> warnings = new TreeSet<>();
        List<String> sortedRoots = roots.stream().distinct().sorted().toList();

        // Phase 1: every concrete version reachable from the roots.
        Map<String, Set<String>> candidates = new TreeMap<>();
        Map<String, Set<String>> requestedBy = new HashMap<>();
        Deque<String[]> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String root : sortedRoots) {
            queue.add(new String[]{root, null});
        }
        while (!queue.isEmpty()) {
            String[] item = queue.poll();
            String[] spec = split(item[0]);
            if (isCore(spec[0])) {
                continue;
            }
            Optional<String> version = concrete(spec[0], spec[1], catalog);
            if (version.isEmpty()) {
                warnings.add(missingMessage(spec[0], spec[1], item[1]));
                continue;
            }
            String key = spec[0] + "#" + version.get();
            candidates.computeIfAbsent(spec[0], k -> new TreeSet<>(PackageVersionComparator.INSTANCE)).add(version.get());
            requestedBy.computeIfAbsent(key, k -> new TreeSet<>()).add(item[1] == null ? "(included)" : item[1]);
            if (visited.add(key)) {
                for (String dep : sortedCopy(catalog.dependencies(spec[0], version.get()))) {
                    queue.add(new String[]{dep, key});
                }
            }
        }

        // Phase 2: one version per package, the highest.
        Map<String, String> chosen = new TreeMap<>();
        candidates.forEach((name, versions) -> {
            String winner = ((TreeSet<String>) versions).last();
            chosen.put(name, winner);
            if (versions.size() > 1) {
                warnings.add("Package " + name + " is required in versions " + versions + "; using " + winner);
            }
        });

        // Phase 3: closure from the roots following only the chosen versions.
        Set<String> resolved = new TreeSet<>();
        Deque<String> closure = new ArrayDeque<>();
        for (String root : sortedRoots) {
            String name = split(root)[0];
            if (chosen.containsKey(name)) {
                closure.add(name);
            }
        }
        while (!closure.isEmpty()) {
            String name = closure.poll();
            String version = chosen.get(name);
            if (version == null || !resolved.add(name + "#" + version)) {
                continue;
            }
            for (String dep : sortedCopy(catalog.dependencies(name, version))) {
                String depName = split(dep)[0];
                if (!isCore(depName) && chosen.containsKey(depName)) {
                    closure.add(depName);
                }
            }
        }
        return new Resolution(List.copyOf(resolved), List.copyOf(warnings));
    }

    private static Optional<String> concrete(String name, String requested, Catalog catalog) {
        Collection<String> stored = catalog.versions(name);
        if (requested == null || requested.isBlank() || requested.equals("latest") || requested.equals("current")) {
            return PackageVersionComparator.max(stored);
        }
        return stored.contains(requested) ? Optional.of(requested) : Optional.empty();
    }

    private static String missingMessage(String name, String version, String requiredBy) {
        String label = version == null ? name : name + "#" + version;
        return "Package " + label + (requiredBy == null ? "" : " (required by " + requiredBy + ")") + " is not stored; skipped";
    }

    private static List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(null);
        return copy;
    }

    public static boolean isCore(String name) {
        return FhirCoreIgPackageType.getNameList().contains(name);
    }

    /**
     * @return {@code [name, version-or-null]}
     */
    static String[] split(String idAndVersion) {
        int i = idAndVersion.indexOf('#');
        return i < 0 ? new String[]{idAndVersion.trim(), null}
                : new String[]{idAndVersion.substring(0, i).trim(), idAndVersion.substring(i + 1).trim()};
    }
}
