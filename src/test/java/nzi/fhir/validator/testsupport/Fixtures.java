package nzi.fhir.validator.testsupport;

import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared access to test fixtures under {@code src/test/resources}.
 */
public final class Fixtures {
    public static final String DK_CORE_IG = "fhir/hl7.fhir.dk.core-3.4.0.tgz";
    public static final String US_CORE_IG = "ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz";
    public static final String DK_PATIENT = "fhir/resources/dk/Patient.json";
    public static final String DK_PRACTITIONER = "fhir/resources/dk/Practitioner.json";
    public static final String DK_CORE_PATIENT_PROFILE = "fhir/profiles/dk/StructureDefinition-dk-core-patient.json";
    public static final String DK_CORE_PATIENT_PROFILE_URL = "http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient";

    private Fixtures() {
    }

    public static byte[] bytes(String classpath) {
        try (InputStream is = Fixtures.class.getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture not found on classpath: " + classpath);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String string(String classpath) {
        return new String(bytes(classpath), StandardCharsets.UTF_8);
    }

    public static Buffer buffer(String classpath) {
        return Buffer.buffer(bytes(classpath));
    }
}
