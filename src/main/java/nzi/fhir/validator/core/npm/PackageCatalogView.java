package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Immutable metadata of every stored IG package at one catalog revision (spec 004, research R4, data-model §2.3).
 * Answers version resolution, existence, declared FHIR versions, dependency resolution and content hashes in memory,
 * so validation against a built context never waits on the database. Never holds package archives.
 */
public final class PackageCatalogView {

    /**
     * Metadata of one stored package.
     *
     * @param sha256 content hash, or {@code null} for rows stored before hashes were recorded
     */
    public record Entry(Set<SupportedFhirVersion> declaredFhirVersions, List<String> dependencies, byte[] sha256,
                        long expandedBytes) {
        public Entry {
            declaredFhirVersions = declaredFhirVersions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(declaredFhirVersions));
            dependencies = List.copyOf(dependencies);
        }
    }

    public static final PackageCatalogView EMPTY = new PackageCatalogView(0, Map.of());

    private final long revision;
    private final Map<String, Entry> packages;
    private final Map<String, Set<String>> versionsByName;

    public PackageCatalogView(long revision, Map<String, Entry> packages) {
        this.revision = revision;
        this.packages = Map.copyOf(packages);
        Map<String, Set<String>> byName = new HashMap<>();
        packages.keySet().forEach(key -> {
            int hash = key.indexOf('#');
            byName.computeIfAbsent(key.substring(0, hash), k -> new HashSet<>()).add(key.substring(hash + 1));
        });
        Map<String, Set<String>> frozen = new HashMap<>();
        byName.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        this.versionsByName = Map.copyOf(frozen);
    }

    public long revision() {
        return revision;
    }

    /** The highest stored version of {@code name} by {@link PackageVersionComparator}, the include rule (FR-002). */
    public Optional<String> highest(String name) {
        return PackageVersionComparator.max(versions(name));
    }

    public Collection<String> versions(String name) {
        return versionsByName.getOrDefault(name, Set.of());
    }

    public boolean contains(String idAndVersion) {
        return packages.containsKey(idAndVersion);
    }

    public Entry entry(String idAndVersion) {
        return packages.get(idAndVersion);
    }

    public byte[] sha256(String idAndVersion) {
        Entry e = packages.get(idAndVersion);
        return e == null ? null : e.sha256();
    }

    public Set<SupportedFhirVersion> declaredFhirVersions(String idAndVersion) {
        Entry e = packages.get(idAndVersion);
        return e == null ? Set.of() : e.declaredFhirVersions();
    }

    /**
     * Whether a stored package may be used for {@code fhirVersion}: it declares no FHIR version (or only unknown
     * labels), or it declares that one. The same rule include has applied since 1.x (CHK038).
     */
    public boolean supports(String idAndVersion, SupportedFhirVersion fhirVersion) {
        Set<SupportedFhirVersion> declared = declaredFhirVersions(idAndVersion);
        return declared.isEmpty() || declared.contains(fhirVersion);
    }

    /** All stored package keys ({@code name#version}). */
    public Set<String> keys() {
        return packages.keySet();
    }

    public DependencyResolver.Catalog asCatalog() {
        return new DependencyResolver.Catalog() {
            @Override
            public Collection<String> versions(String name) {
                return PackageCatalogView.this.versions(name);
            }

            @Override
            public List<String> dependencies(String name, String version) {
                Entry e = packages.get(name + "#" + version);
                return e == null ? List.of() : e.dependencies();
            }
        };
    }

    /** FHIR releases a stored package declares ({@code fhirVersions}, else {@code fhirVersion}); unknown labels are skipped. */
    public static Set<SupportedFhirVersion> parseDeclaredFhirVersions(JsonObject meta) {
        Set<SupportedFhirVersion> declared = new java.util.TreeSet<>();
        if (meta == null) {
            return declared;
        }
        JsonArray list = meta.getJsonArray("fhirVersions");
        if (list != null) {
            list.forEach(v -> SupportedFhirVersion.fromDeclaredVersion(String.valueOf(v)).ifPresent(declared::add));
        }
        if (declared.isEmpty() && meta.getValue("fhirVersion") instanceof String single) {
            SupportedFhirVersion.fromDeclaredVersion(single).ifPresent(declared::add);
        }
        return declared;
    }

    /** Reads the catalog revision and every package's metadata (never {@code content_raw}). */
    public static Future<PackageCatalogView> load(SqlClient client) {
        return client.query("SELECT revision FROM " + DB_POSTGRES_SCHEMA_NAME + ".fhir_package_catalog WHERE id = 1").execute()
                .compose(rev -> {
                    long revision = rev.size() == 0 ? 0 : rev.iterator().next().getLong(0);
                    return client.query("SELECT ig_package_id, ig_package_version, ig_package_meta, dependencies, content_sha256, "
                                    + "expanded_bytes FROM " + DB_POSTGRES_SCHEMA_NAME + ".fhir_implementation_guides")
                            .execute()
                            .map(rows -> {
                                Map<String, Entry> packages = new HashMap<>();
                                for (Row row : rows) {
                                    String[] deps = row.getArrayOfStrings(3);
                                    Object meta = row.getValue(2);
                                    io.vertx.core.buffer.Buffer sha = row.getBuffer(4);
                                    Long expanded = row.getLong(5);
                                    packages.put(row.getString(0) + "#" + row.getString(1), new Entry(
                                            parseDeclaredFhirVersions(meta instanceof JsonObject o ? o : null),
                                            deps == null ? List.of() : List.of(deps),
                                            sha == null ? null : sha.getBytes(),
                                            expanded == null ? 0 : expanded));
                                }
                                return new PackageCatalogView(revision, packages);
                            });
                });
    }
}
