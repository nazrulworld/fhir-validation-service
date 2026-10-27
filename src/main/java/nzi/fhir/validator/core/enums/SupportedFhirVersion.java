package nzi.fhir.validator.core.enums;

import org.apache.commons.lang3.Validate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Enum representing the FHIR versions known to the service. Only {@link #isEnabled() enabled} versions are served;
 * R6 is known (it exists in the database enum type) but disabled until separately specified.
 *
 * @author Md Nazrul Islam
 */
public enum SupportedFhirVersion {
    STU3(true),
    R4(true),
    R4B(true),
    R5(true),
    R6(false);

    private final boolean enabled;

    SupportedFhirVersion(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the enabled versions in declaration order (STU3, R4, R4B, R5)
     */
    public static List<SupportedFhirVersion> enabled() {
        return Arrays.stream(values()).filter(SupportedFhirVersion::isEnabled).toList();
    }

    /**
     * Parses a path/version parameter case-insensitively, accepting enabled versions only.
     */
    public static Optional<SupportedFhirVersion> parseEnabled(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        try {
            SupportedFhirVersion v = valueOf(version.trim().toUpperCase(Locale.ROOT));
            return v.isEnabled() ? Optional.of(v) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks if a given string is a valid FHIR version.
     *
     * @param version The version string to check
     * @return true if the version is supported, false otherwise
     */
    public static boolean isValid(String version) {
        if (version == null) {
            return false;
        }

        try {
            valueOf(version.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    public static String toReleaseName(SupportedFhirVersion version) {
        return switch (version) {
            case STU3 -> "R3";
            case R4 -> "R4";
            case R4B -> "R4B";
            case R5 -> "R5";
            case R6 -> "R6";
        };
    }
    public static String toReleaseName(SupportedFhirVersion version, boolean lowercase) {
        String releaseName = toReleaseName(version);
        if (lowercase) {
            return releaseName.toLowerCase();
        }
        return releaseName;
    }

    public static SupportedFhirVersion fromVersionNumber(String versionNumber) {

        Validate.notNull(versionNumber, "versionNumber must not be null");

        return switch (versionNumber) {
            case "3.0.0", "3.0.1", "3.0.2" -> STU3;
            case "4.0.0", "4.0.1" -> R4;
            case "4.3.0" -> R4B;
            case "5.0.0" -> R5;
            case "6.0.0" -> R6;
            default -> throw new IllegalArgumentException("Unsupported or invalid FHIR version: " + versionNumber);
        };
    }
    /**
     * The release a package's declared FHIR version belongs to, by major.minor ("4.0.1" and "4.0" → R4,
     * "5.0.0-ballot" → R5); empty when the label is not a known release.
     */
    public static java.util.Optional<SupportedFhirVersion> fromDeclaredVersion(String declared) {
        if (declared == null) {
            return java.util.Optional.empty();
        }
        String v = declared.trim();
        if (v.startsWith("3.0")) return java.util.Optional.of(STU3);
        if (v.startsWith("4.0")) return java.util.Optional.of(R4);
        if (v.startsWith("4.3")) return java.util.Optional.of(R4B);
        if (v.startsWith("5.0")) return java.util.Optional.of(R5);
        if (v.startsWith("6.0")) return java.util.Optional.of(R6);
        return java.util.Optional.empty();
    }

    /**
     * Gets the default FHIR version.
     *
     * @return The default FHIR version (R4)
     */
    public static SupportedFhirVersion getDefault() {
        return R4;
    }
}
