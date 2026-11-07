package nzi.fhir.validator.testsupport;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Builds small FHIR profiles and NPM packages in memory for tests.
 */
public final class TestPackages {

    private TestPackages() {
    }

    /**
     * An R4 Patient profile (differential only) that makes the given elements mandatory.
     */
    public static JsonObject requiredPatientElements(String url, String... elementNames) {
        JsonArray elements = new JsonArray().add(new JsonObject().put("id", "Patient").put("path", "Patient"));
        for (String name : elementNames) {
            elements.add(new JsonObject().put("id", "Patient." + name).put("path", "Patient." + name).put("min", 1));
        }
        String name = "TestPatient" + Math.abs(url.hashCode());
        return new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("id", name.toLowerCase())
                .put("url", url)
                .put("name", name)
                .put("status", "active")
                .put("fhirVersion", "4.0.1")
                .put("kind", "resource")
                .put("abstract", false)
                .put("type", "Patient")
                .put("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Patient")
                .put("derivation", "constraint")
                .put("differential", new JsonObject().put("element", elements));
    }

    /**
     * An NPM package ({@code .tgz}) containing the given resources.
     *
     * @param dependencies {@code name -> version}
     */
    public static byte[] npmPackage(String name, String version, Map<String, String> dependencies, List<JsonObject> resources) {
        return igPackage(name, version, List.of("4.0.1"), dependencies, resources.toArray(JsonObject[]::new));
    }

    /**
     * An NPM package ({@code .tgz}) whose {@code package.json} declares the given FHIR versions (spec 004: an
     * {@code ig} for another FHIR version is refused).
     */
    public static byte[] igPackage(String name, String version, List<String> fhirVersions, Map<String, String> dependencies,
                                   JsonObject... resources) {
        JsonObject deps = new JsonObject().put("hl7.fhir.r4.core", "4.0.1");
        dependencies.forEach(deps::put);
        JsonObject packageJson = new JsonObject()
                .put("name", name)
                .put("version", version)
                .put("fhirVersions", new JsonArray(List.copyOf(fhirVersions)))
                .put("type", "fhir.ig")
                .put("dependencies", deps);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            add(tar, "package/package.json", packageJson.encodePrettily());
            for (JsonObject r : resources) {
                add(tar, "package/" + r.getString("resourceType") + "-" + r.getString("id") + ".json", r.encode());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * An R4 IG package ({@code .tgz}) holding the given StructureDefinitions, the replacement for registering
     * loose profiles (removed in 2.0.0).
     */
    public static byte[] profilePackage(String name, String version, JsonObject... structureDefinitions) {
        return npmPackage(name, version, Map.of(), List.of(structureDefinitions));
    }

    /**
     * Stores {@code tgz} and includes it for R4; completes once this instance has applied the change (or answered
     * with a still-applying warning).
     */
    public static io.vertx.core.Future<Void> includePackage(nzi.fhir.validator.ServiceRegistry services, byte[] tgz,
                                                             String name, String version) {
        return services.igPackageService().registerIg(tgz, false)
                .compose(pkg -> services.igPackageService()
                        .include(nzi.fhir.validator.core.enums.SupportedFhirVersion.R4, name, version))
                .mapEmpty();
    }

    /**
     * The same package archive with its non-core dependencies removed from {@code package.json}, so a real published
     * IG (e.g. US Core) can be built on demand without its dependencies being stored.
     */
    public static byte[] withoutDependencies(byte[] tgz) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (org.apache.commons.compress.archivers.tar.TarArchiveInputStream in =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                             new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(new java.io.ByteArrayInputStream(tgz)));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("package/package.json")) {
                    JsonObject pkg = new JsonObject(new String(data, StandardCharsets.UTF_8));
                    JsonObject deps = new JsonObject();
                    JsonObject original = pkg.getJsonObject("dependencies", new JsonObject());
                    original.fieldNames().stream().filter(n -> n.matches("hl7\\.fhir\\.r[0-9]b?\\.core"))
                            .forEach(n -> deps.put(n, original.getValue(n)));
                    data = pkg.put("dependencies", deps).encode().getBytes(StandardCharsets.UTF_8);
                }
                TarArchiveEntry copy = new TarArchiveEntry(entry.getName());
                copy.setSize(data.length);
                tar.putArchiveEntry(copy);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Stores {@code tgz} without including it (it can still be named in {@code ig}, spec 004 FR-006). */
    public static io.vertx.core.Future<Void> storePackage(nzi.fhir.validator.ServiceRegistry services, byte[] tgz) {
        return services.igPackageService().registerIg(tgz, false).mapEmpty();
    }

    private static void add(TarArchiveOutputStream tar, String path, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(path);
        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    /** Whether the outcome has an error or fatal issue whose message or location mentions {@code text}. */
    public static boolean hasError(nzi.fhir.validator.core.validator.ValidatorSnapshot.Outcome o, String text) {
        return o.result().getMessages().stream().anyMatch(m ->
                (m.getSeverity() == ca.uhn.fhir.validation.ResultSeverityEnum.ERROR
                        || m.getSeverity() == ca.uhn.fhir.validation.ResultSeverityEnum.FATAL)
                        && (m.getMessage() + " " + m.getLocationString()).contains(text));
    }

    /** A collection Bundle of {@code entries} Patients (validation cost grows with the size). */
    public static String patientBundle(int entries) {
        io.vertx.core.json.JsonArray list = new io.vertx.core.json.JsonArray();
        for (int i = 0; i < entries; i++) {
            list.add(new JsonObject().put("fullUrl", "urn:uuid:" + java.util.UUID.randomUUID())
                    .put("resource", new JsonObject().put("resourceType", "Patient").put("active", true)
                            .put("name", new io.vertx.core.json.JsonArray().add(new JsonObject().put("family", "Family" + i)))));
        }
        return new JsonObject().put("resourceType", "Bundle").put("type", "collection").put("entry", list).encode();
    }

    // --- organisation ownership (spec 005) --------------------------------------------------------------------

    private static final String SCHEMA = nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

    /** Inserts an active {@code COMMERCIAL} organisation by SQL; completes with its id. */
    public static io.vertx.core.Future<java.util.UUID> createOrganisation(io.vertx.sqlclient.Pool pool, String nameShort) {
        return pool.preparedQuery("INSERT INTO " + SCHEMA + ".organisations (name_short, name_long, type) "
                        + "VALUES ($1, $2, 'COMMERCIAL') RETURNING id")
                .execute(io.vertx.sqlclient.Tuple.of(nameShort, nameShort + " Ltd"))
                .map(rows -> rows.iterator().next().getUUID(0));
    }

    /**
     * Inserts an API key owned by {@code owner} with {@code role} ({@code ADMIN} or {@code VALIDATE}); completes with
     * the key (43 random characters, only its SHA-256 hash is stored).
     */
    public static io.vertx.core.Future<String> createKey(io.vertx.sqlclient.Pool pool, java.util.UUID owner, String name,
                                                         String role) {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return pool.preparedQuery("INSERT INTO " + SCHEMA + ".api_keys (name, key_hash, role, owner) VALUES ($1, $2, $3, $4)")
                .execute(io.vertx.sqlclient.Tuple.of(name,
                        io.vertx.core.buffer.Buffer.buffer(nzi.fhir.validator.core.security.ApiKeyRepository.hash(key)), role, owner))
                .map(key);
    }

    /**
     * Sets a stored package's owner and sharing flag by plain SQL, bumping the catalog revision and notifying
     * {@code PACKAGE} in the same transaction, as a real access change does.
     */
    public static io.vertx.core.Future<Void> setPackageAccess(io.vertx.sqlclient.Pool pool, String nameAndVersion,
                                                              java.util.UUID owner, boolean shareable) {
        int hash = nameAndVersion.indexOf('#');
        return pool.withTransaction(tx -> tx.preparedQuery("UPDATE " + SCHEMA + ".fhir_implementation_guides "
                                + "SET owner = $1, is_shareable = $2 WHERE ig_package_id = $3 AND ig_package_version = $4")
                        .execute(io.vertx.sqlclient.Tuple.of(owner, shareable, nameAndVersion.substring(0, hash),
                                nameAndVersion.substring(hash + 1)))
                        .compose(rows -> rows.rowCount() == 1 ? io.vertx.core.Future.succeededFuture()
                                : io.vertx.core.Future.failedFuture("Package " + nameAndVersion + " is not stored"))
                        .compose(v -> tx.query("UPDATE " + SCHEMA + ".fhir_package_catalog SET revision = revision + 1 "
                                + "WHERE id = 1 RETURNING revision").execute())
                        .compose(rows -> tx.preparedQuery("SELECT pg_notify($1, $2)").execute(io.vertx.sqlclient.Tuple.of(
                                nzi.fhir.validator.core.service.ConfigRevisionRepository.CHANNEL,
                                new JsonObject().put("kind", "PACKAGE").put("key", nameAndVersion)
                                        .put("revision", rows.iterator().next().getLong(0)).encode()))))
                .mapEmpty();
    }

    /** A Patient without the elements the test profiles require. */
    public static String minimalPatient() {
        return new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true).encode();
    }
}
