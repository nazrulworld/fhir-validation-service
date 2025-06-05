package nzi.fhir.validator.web.enums;

import org.apache.commons.lang3.Validate;

/**
 * Enum representing the supported FHIR versions.
 * 
 * @author Md Nazrul Islam
 */
public enum SupportedFhirVersion {
    STU3,
    R4,
    R4B,
    R5,
    R6;
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
     * Gets the default FHIR version.
     *
     * @return The default FHIR version (R4)
     */
    public static SupportedFhirVersion getDefault() {
        return R4;
    }
}