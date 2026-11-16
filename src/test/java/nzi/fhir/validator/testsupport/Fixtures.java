package nzi.fhir.validator.testsupport;

import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Spec 010: the two R4 contexts of the shared-definitions tests (they share 10 packages). */
    public static final String DK_CORE_350 = "hl7.fhir.dk.core#3.5.0";
    public static final String MEDCOM_CORE_400 = "medcom.fhir.dk.core#4.0.0";

    /** Classpath of the lockfile of downloaded test packages (spec 010 T001). */
    public static final String PACKAGE_LOCK = "ig/packages/010-packages.lock";

    /** The folder {@code scripts/fetch-test-packages.sh} downloads into. */
    public static Path lockedPackageDir() {
        String dir = System.getenv("FV_TEST_PACKAGES");
        return dir != null && !dir.isBlank() ? Path.of(dir)
                : Path.of(System.getProperty("user.home"), ".cache", "fv-test-packages");
    }

    /** Every {@code name#version} in the lockfile, in file order. */
    public static List<String> lockedPackages() {
        List<String> ids = new ArrayList<>();
        for (String line : string(PACKAGE_LOCK).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                ids.add(trimmed.split("\\s+")[0]);
            }
        }
        return ids;
    }

    /**
     * The archive of a locked test package. Never skipped silently: a missing file fails the test with the command
     * that downloads it (tests stay offline).
     */
    public static byte[] lockedPackage(String nameVersion) {
        if (!lockedPackages().contains(nameVersion)) {
            throw new IllegalArgumentException(nameVersion + " is not in " + PACKAGE_LOCK);
        }
        Path file = lockedPackageDir().resolve(nameVersion + ".tgz");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Test package " + nameVersion + " is missing from " + lockedPackageDir()
                    + ": run scripts/fetch-test-packages.sh");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
