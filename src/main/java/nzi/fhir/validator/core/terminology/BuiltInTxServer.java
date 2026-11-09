package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Arrays;
import java.util.List;

/**
 * The built-in fallback list of approved terminology servers (spec 007 FR-012, clarification Q4): the default servers
 * the official registry listed when this release was made. It is fixed in the release and can't be configured, and it
 * is the allowlist <em>only</em> for a FHIR version for which no registry list has ever been obtained. Once one has
 * been, the registry list alone decides. STU3 and R4B have no entry: the registry lists no server for them (research
 * R8).
 */
public enum BuiltInTxServer {
    TX_FHIR_ORG_R4("https://tx.fhir.org/r4", SupportedFhirVersion.R4),
    TX_FHIR_ORG_R5("https://tx.fhir.org/r5", SupportedFhirVersion.R5);

    private final String url;
    private final SupportedFhirVersion fhirVersion;

    BuiltInTxServer(String url, SupportedFhirVersion fhirVersion) {
        this.url = url;
        this.fhirVersion = fhirVersion;
    }

    public String url() {
        return url;
    }

    public SupportedFhirVersion fhirVersion() {
        return fhirVersion;
    }

    /** The built-in servers of {@code version} (possibly none). */
    public static List<BuiltInTxServer> forVersion(SupportedFhirVersion version) {
        return Arrays.stream(values()).filter(s -> s.fhirVersion == version).toList();
    }
}
