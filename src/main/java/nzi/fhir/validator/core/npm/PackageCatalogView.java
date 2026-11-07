package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.security.Caller;

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
     * @param access owner and sharing of the package (spec 005)
     */
    public record Entry(Set<SupportedFhirVersion> declaredFhirVersions, List<String> dependencies, byte[] sha256,
                        long expandedBytes, Access access) {
        public Entry {
            declaredFhirVersions = declaredFhirVersions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(declaredFhirVersions));
            dependencies = List.copyOf(dependencies);
            access = access == null ? Access.systemShared(Organisations.DEFAULT_SYSTEM_SHORT_NAME) : access;
        }

        /** A package owned by the system organisation and shareable (the 3.0 behaviour). */
        public Entry(Set<SupportedFhirVersion> declaredFhirVersions, List<String> dependencies, byte[] sha256,
                     long expandedBytes) {
            this(declaredFhirVersions, dependencies, sha256, expandedBytes, null);
        }
    }

    public static final PackageCatalogView EMPTY = new PackageCatalogView(0, Map.of());

    private final long revision;
    private final String systemShortName;
    private final Map<String, Entry> packages;
    private final Map<String, Set<String>> versionsByName;

    public PackageCatalogView(long revision, Map<String, Entry> packages) {
        this(revision, packages, Organisations.DEFAULT_SYSTEM_SHORT_NAME);
    }

    /**
     * @param systemShortName the system organisation's current short name (spec 005: the owner of the base context);
     *                        a rename bumps the catalog revision, so every instance's view picks it up
     */
    public PackageCatalogView(long revision, Map<String, Entry> packages, String systemShortName) {
        this.revision = revision;
        this.systemShortName = systemShortName;
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

    public String systemShortName() {
        return systemShortName;
    }

    public long revision() {
        return revision;
    }

    /** The highest stored version of {@code name} by {@link PackageVersionComparator}, the include rule (FR-002). */
    public Optional<String> highest(String name) {
        return PackageVersionComparator.max(versions(name));
    }

    /**
     * The highest stored version of {@code name} that {@code caller} may use (spec 005 research R5): a versionless
     * reference never resolves to, or reveals, a version the caller can't see.
     */
    public Optional<String> highestVisible(String name, Caller caller) {
        Collection<String> all = versions(name);
        if (caller.system()) {
            return PackageVersionComparator.max(all);
        }
        List<String> visible = new java.util.ArrayList<>(all.size());
        for (String v : all) {
            if (AccessPolicy.canUse(caller, access(name + "#" + v))) {
                visible.add(v);
            }
        }
        return PackageVersionComparator.max(visible);
    }

    /** Owner and sharing of a stored package, or {@code null} when it isn't stored. */
    public Access access(String idAndVersion) {
        Entry e = packages.get(idAndVersion);
        return e == null ? null : e.access();
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
        return client.query("SELECT c.revision, (SELECT name_short FROM " + DB_POSTGRES_SCHEMA_NAME + ".organisations WHERE id = '"
                        + Organisations.SYSTEM_ID + "') FROM " + DB_POSTGRES_SCHEMA_NAME + ".fhir_package_catalog c WHERE c.id = 1").execute()
                .compose(rev -> {
                    long revision = rev.size() == 0 ? 0 : rev.iterator().next().getLong(0);
                    String systemName = rev.size() == 0 || rev.iterator().next().getString(1) == null
                            ? Organisations.DEFAULT_SYSTEM_SHORT_NAME : rev.iterator().next().getString(1);
                    return client.query("SELECT g.ig_package_id, g.ig_package_version, g.ig_package_meta, g.dependencies, "
                                    + "g.content_sha256, g.expanded_bytes, g.owner, o.name_short, g.is_shareable FROM "
                                    + DB_POSTGRES_SCHEMA_NAME + ".fhir_implementation_guides g JOIN " + DB_POSTGRES_SCHEMA_NAME
                                    + ".organisations o ON o.id = g.owner")
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
                                            expanded == null ? 0 : expanded,
                                            new Access(row.getUUID(6), row.getString(7), row.getBoolean(8))));
                                }
                                return new PackageCatalogView(revision, packages, systemName);
                            });
                });
    }
}
