package nzi.fhir.validator.web.endpoint;

import nzi.fhir.validator.core.enums.ServedVersions;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.ProblemException;

/**
 * The {@code {version}} path parameter (spec 009, research R4). Which versions a request may name depends on what it
 * does: validation and reads need a served version; adding a context refuses a supported but unserved version with 422;
 * changing or removing a stored context accepts any supported version (FR-032).
 */
final class VersionParams {

    private VersionParams() {
    }

    /** A served version, or 400 {@code unsupported-fhir-version} as before (FR-005). */
    static SupportedFhirVersion served(String param, ServedVersions served) {
        return served.find(param).orElseThrow(() -> ProblemException.unsupportedVersion(param, served));
    }

    /**
     * For {@code POST /{version}/validator}: a served version; a supported but unserved one is 422
     * {@code fhir-version-disabled} (FR-008); anything else 400.
     */
    static SupportedFhirVersion servedForInclude(String param, ServedVersions served) {
        SupportedFhirVersion version = SupportedFhirVersion.parseEnabled(param)
                .orElseThrow(() -> ProblemException.unsupportedVersion(param, served));
        if (!served.contains(version)) {
            throw ProblemException.fhirVersionDisabled(version, served);
        }
        return version;
    }

    /** For changing or removing a stored context: any version this release supports, served or not (FR-032). */
    static SupportedFhirVersion supported(String param, ServedVersions served) {
        return SupportedFhirVersion.parseEnabled(param).orElseThrow(() -> ProblemException.unsupportedVersion(param, served));
    }
}
