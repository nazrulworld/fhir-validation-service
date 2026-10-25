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
        JsonObject deps = new JsonObject().put("hl7.fhir.r4.core", "4.0.1");
        dependencies.forEach(deps::put);
        JsonObject packageJson = new JsonObject()
                .put("name", name)
                .put("version", version)
                .put("fhirVersions", new JsonArray().add("4.0.1"))
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

    /** A Patient without the elements the test profiles require. */
    public static String minimalPatient() {
        return new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true).encode();
    }
}
