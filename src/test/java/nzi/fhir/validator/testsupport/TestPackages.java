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

    // ---- Spec 012: packages for two-IG contexts ----

    public static final String MULTI_A = "test.multi.a";
    public static final String MULTI_B = "test.multi.b";
    public static final String MULTI_C = "test.multi.c";
    public static final String MULTI_A_PROFILE = "http://example.org/fhir/StructureDefinition/multi-a-patient";
    public static final String MULTI_B_PROFILE = "http://example.org/fhir/StructureDefinition/multi-b-patient";
    public static final String MULTI_C_PROFILE = "http://example.org/fhir/StructureDefinition/multi-c-patient";

    /** {@code test.multi.a#1.0.0} (R4): a Patient profile requiring {@code birthDate}. */
    public static byte[] multiA() {
        return npmPackage(MULTI_A, "1.0.0", Map.of(), List.of(requiredPatientElements(MULTI_A_PROFILE, "birthDate")));
    }

    /** {@code test.multi.b#2.0.0} (R4): a Patient profile requiring {@code gender}. */
    public static byte[] multiB() {
        return npmPackage(MULTI_B, "2.0.0", Map.of(), List.of(requiredPatientElements(MULTI_B_PROFILE, "gender")));
    }

    /** {@code test.multi.c#3.0.0} (R4): a Patient profile requiring {@code name}. */
    public static byte[] multiC() {
        return npmPackage(MULTI_C, "3.0.0", Map.of(), List.of(requiredPatientElements(MULTI_C_PROFILE, "name")));
    }

    /** {@code test.multi.x#version} (R4), a plain dependency. */
    public static byte[] conflictX(String version) {
        return npmPackage("test.multi.x", version, Map.of(), List.of());
    }

    /** {@code test.multi.p#1.0.0} depends on {@code test.multi.x#1.0.0}. */
    public static byte[] multiNeedsX1() {
        return npmPackage("test.multi.p", "1.0.0", Map.of("test.multi.x", "1.0.0"), List.of());
    }

    /** {@code test.multi.q#1.0.0} depends on {@code test.multi.x#2.0.0}. */
    public static byte[] multiNeedsX2() {
        return npmPackage("test.multi.q", "1.0.0", Map.of("test.multi.x", "2.0.0"), List.of());
    }

    /** A stand-in {@code hl7.terminology.r4#version} (no resources). */
    public static byte[] terminologyPackage(String version) {
        return npmPackage("hl7.terminology.r4", version, Map.of(), List.of());
    }

    /** {@code test.multi.t1#1.0.0} depends on {@code hl7.terminology.r4#5.0.0}. */
    public static byte[] multiNeedsTx1() {
        return npmPackage("test.multi.t1", "1.0.0", Map.of("hl7.terminology.r4", "5.0.0"), List.of());
    }

    /** {@code test.multi.t2#1.0.0} depends on {@code hl7.terminology.r4#6.0.2}. */
    public static byte[] multiNeedsTx2() {
        return npmPackage("test.multi.t2", "1.0.0", Map.of("hl7.terminology.r4", "6.0.2"), List.of());
    }

    /**
     * Includes two stored IG packages as one context for {@code fhirVersion} (spec 012) as the system organisation.
     *
     * @return the include response
     */
    public static io.vertx.core.Future<JsonObject> includePair(nzi.fhir.validator.ServiceRegistry services,
                                                                nzi.fhir.validator.core.enums.SupportedFhirVersion fhirVersion,
                                                                String a, String aVersion, String b, String bVersion) {
        return services.igPackageService().includePair(
                nzi.fhir.validator.core.security.Caller.system(nzi.fhir.validator.core.org.Organisations.DEFAULT_SYSTEM_SHORT_NAME),
                fhirVersion,
                new nzi.fhir.validator.core.service.IgPackageService.IgEntry(a, aVersion),
                new nzi.fhir.validator.core.service.IgPackageService.IgEntry(b, bVersion),
                java.util.Optional.empty(), java.util.Optional.empty());
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

    // --- terminology (spec 007) ---------------------------------------------------------------------------------

    /** The local, complete CodeSystem of {@link #terminologyIg()}. */
    public static final String TX_LOCAL_SYSTEM = "http://example.org/fhir/CodeSystem/local";
    /** The ValueSet of {@link #terminologyIg()}: the local system plus all of LOINC. */
    public static final String TX_MIXED_VALUESET = "http://example.org/fhir/ValueSet/mixed";
    /** The Observation profile of {@link #terminologyIg()}: {@code Observation.code} bound (required) to the mixed set. */
    public static final String TX_OBSERVATION_PROFILE = "http://example.org/fhir/StructureDefinition/tx-observation";
    public static final String TX_IG_NAME = "test.tx.ig";
    public static final String TX_IG_VERSION = "1.0.0";

    /**
     * {@code test.tx.ig#1.0.0} (R4): a complete local CodeSystem ({@code local-a}, {@code local-b}), a ValueSet that
     * includes that system and LOINC, and an Observation profile binding {@code code} to the ValueSet (required).
     */
    public static byte[] terminologyIg() {
        JsonObject codeSystem = new JsonObject()
                .put("resourceType", "CodeSystem").put("id", "local").put("url", TX_LOCAL_SYSTEM)
                .put("version", "1.0.0").put("name", "Local").put("status", "active").put("content", "complete")
                .put("concept", new JsonArray()
                        .add(new JsonObject().put("code", "local-a").put("display", "Local A"))
                        .add(new JsonObject().put("code", "local-b").put("display", "Local B")));
        JsonObject valueSet = new JsonObject()
                .put("resourceType", "ValueSet").put("id", "mixed").put("url", TX_MIXED_VALUESET)
                .put("version", "1.0.0").put("name", "Mixed").put("status", "active")
                .put("compose", new JsonObject().put("include", new JsonArray()
                        .add(new JsonObject().put("system", TX_LOCAL_SYSTEM))
                        .add(new JsonObject().put("system", "http://loinc.org"))));
        JsonObject profile = new JsonObject()
                .put("resourceType", "StructureDefinition").put("id", "tx-observation").put("url", TX_OBSERVATION_PROFILE)
                .put("name", "TxObservation").put("status", "active").put("fhirVersion", "4.0.1")
                .put("kind", "resource").put("abstract", false).put("type", "Observation")
                .put("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Observation")
                .put("derivation", "constraint")
                .put("differential", new JsonObject().put("element", new JsonArray()
                        .add(new JsonObject().put("id", "Observation").put("path", "Observation"))
                        .add(new JsonObject().put("id", "Observation.code").put("path", "Observation.code")
                                .put("binding", new JsonObject().put("strength", "required").put("valueSet", TX_MIXED_VALUESET)))));
        return npmPackage(TX_IG_NAME, TX_IG_VERSION, Map.of(), List.of(codeSystem, valueSet, profile));
    }

    /** An R4 Observation coded with {@code system|code} and conforming to the {@link #terminologyIg()} profile. */
    public static String codedObservation(String system, String code) {
        return new JsonObject().put("resourceType", "Observation").put("id", "o1").put("status", "final")
                .put("meta", new JsonObject().put("profile", new JsonArray().add(TX_OBSERVATION_PROFILE)))
                .put("code", new JsonObject().put("coding", new JsonArray()
                        .add(new JsonObject().put("system", system).put("code", code))))
                .encode();
    }

    /**
     * A collection Bundle of {@code entries} Observations, each with a distinct LOINC code and the {@link #terminologyIg()}
     * profile, so every code is checked against the mixed ValueSet (budget tests).
     */
    public static String loincBundle(int entries) {
        JsonArray list = new JsonArray();
        for (int i = 0; i < entries; i++) {
            list.add(new JsonObject().put("fullUrl", "urn:uuid:" + java.util.UUID.randomUUID())
                    .put("resource", new JsonObject().put("resourceType", "Observation").put("status", "final")
                            .put("meta", new JsonObject().put("profile", new JsonArray().add(TX_OBSERVATION_PROFILE)))
                            .put("code", new JsonObject().put("coding", new JsonArray()
                                    .add(new JsonObject().put("system", "http://loinc.org").put("code", (10000 + i) + "-0"))))));
        }
        return new JsonObject().put("resourceType", "Bundle").put("type", "collection").put("entry", list).encode();
    }

    /** A Patient without the elements the test profiles require. */
    public static String minimalPatient() {
        return new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true).encode();
    }

    // ---- Spec 013: a regex that runs away on purpose ----

    public static final String REGEX_TRAP = "test.regex.trap";
    public static final String REGEX_TRAP_PROFILE = "http://example.org/fhir/StructureDefinition/regex-trap-patient";
    /** Backtracks catastrophically on {@link #REGEX_TRAP_VALUE}: every check runs past HL7 core's 500 ms limit. */
    public static final String RUNAWAY_REGEX = "^(.*?,){20}P$";
    public static final String REGEX_TRAP_VALUE = "a,".repeat(30);

    /**
     * {@code test.regex.trap#1.0.0} (R4): a Patient profile whose {@code Patient.name.family} must match
     * {@link #RUNAWAY_REGEX}, through a FHIRPath {@code matches()} constraint (research R7: reported as
     * {@code REGEX_MATCH_TIMED_OUT} when it times out).
     */
    public static byte[] regexTrapPackage() {
        JsonObject profile = requiredPatientElements(REGEX_TRAP_PROFILE);
        profile.getJsonObject("differential").getJsonArray("element").add(new JsonObject()
                .put("id", "Patient.name.family").put("path", "Patient.name.family")
                .put("constraint", new JsonArray().add(new JsonObject()
                        .put("key", "trap-1").put("severity", "error").put("human", "family matches a runaway regex")
                        .put("expression", "matches('" + RUNAWAY_REGEX + "')")
                        .put("source", REGEX_TRAP_PROFILE))));
        return npmPackage(REGEX_TRAP, "1.0.0", Map.of(), List.of(profile));
    }

    /** A Patient claiming {@link #REGEX_TRAP_PROFILE} with {@code values} names whose family is {@link #REGEX_TRAP_VALUE}. */
    public static String regexTrapPatient(int values) {
        JsonArray names = new JsonArray();
        for (int i = 0; i < values; i++) {
            names.add(new JsonObject().put("family", REGEX_TRAP_VALUE));
        }
        return new JsonObject().put("resourceType", "Patient").put("id", "trap")
                .put("meta", new JsonObject().put("profile", new JsonArray().add(REGEX_TRAP_PROFILE)))
                .put("name", names).encode();
    }
}
