package nzi.fhir.validator.web;

import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A failure that maps to a specific RFC 9457 problem response. {@code detail} is client-facing and must be curated:
 * never an exception message, class name or stack trace.
 */
public class ProblemException extends RuntimeException {
    private final int status;
    private final String type;
    private final String detail;
    private final JsonObject extensions = new JsonObject();
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ProblemException(int status, String type, String detail) {
        super(type + ": " + detail, null, false, false);
        this.status = status;
        this.type = type;
        this.detail = detail;
    }

    public static ProblemException notReady(String detail) {
        return new ProblemException(503, "not-ready", detail).withHeader("Retry-After", "5");
    }

    public static ProblemException unsupportedVersion(String version) {
        return new ProblemException(400, "unsupported-fhir-version",
                "FHIR version '" + version + "' is not supported. Supported versions: STU3, R4, R4B, R5");
    }

    /**
     * Requested profiles that none of the version's definitions (core, included IGs and their dependencies) define.
     * The URLs are client input, so echoing them is safe; nothing internal is exposed.
     */
    public static ProblemException unknownProfiles(nzi.fhir.validator.core.enums.SupportedFhirVersion version,
                                                   java.util.List<String> unresolved) {
        return unknownProfiles(version, null, unresolved);
    }

    /**
     * @param igPackage the request's IG package ({@code name#version}), or {@code null} for the core-only base context
     */
    public static ProblemException unknownProfiles(nzi.fhir.validator.core.enums.SupportedFhirVersion version, String igPackage,
                                                   java.util.List<String> unresolved) {
        String context = igPackage == null ? "the core specification of FHIR " + version
                : "IG package " + igPackage + " (FHIR " + version + "), its dependencies or the core specification";
        return new ProblemException(400, TYPE_UNKNOWN_PROFILE, "One or more requested profiles are not defined by "
                + context + "; name the IG that defines them with the ig parameter")
                .withExtension("unresolvedProfiles", new io.vertx.core.json.JsonArray(java.util.List.copyOf(unresolved)));
    }

    /** The {@code profile} list exceeds the fixed limits (FR-006a); the URLs are deliberately not echoed. */
    public static ProblemException profileLimits() {
        return new ProblemException(400, "invalid-request",
                "At most 10 profiles may be requested, each at most 1024 characters");
    }

    public static final String TYPE_UNKNOWN_PROFILE = "unknown-profile";
    public static final String TYPE_PACKAGE_NOT_FOUND = "package-not-found";
    public static final String TYPE_DEPENDENCY_NOT_FOUND = "dependency-not-found";
    public static final String TYPE_CONFLICT = "conflict";
    /** Spec 007 FR-010: a terminology server the official registry doesn't list for the FHIR version. */
    public static final String TYPE_TX_SERVER_NOT_REGISTERED = "tx-server-not-registered";
    /** Spec 007 FR-012a: no registry list was ever obtained, and the server is not on the built-in fallback list. */
    public static final String TYPE_TX_REGISTRY_UNAVAILABLE = "tx-registry-unavailable";
    /** The official HL7 terminology registry, named in {@link #txServerNotRegistered} (spec 007 FR-006). */
    public static final String TX_REGISTRY = "https://ecosystem.hl7.org/tx-reg";

    /**
     * A validation request names an IG package version that is stored and usable but not included for the FHIR version
     * (spec 007 FR-022, clarification Q1): 400 {@code invalid-request}. Only answered when the caller may see the
     * package, so it discloses nothing.
     *
     * @param key always a concrete {@code name#version}
     */
    public static ProblemException igNotIncluded(String key, nzi.fhir.validator.core.enums.SupportedFhirVersion version) {
        return new ProblemException(400, "invalid-request", "IG package " + key + " is not included for " + version
                + "; include it first (POST /" + version + "/validator)");
    }

    /** Spec 007 FR-010 (a): the terminology server is not on the registry's list for {@code version}. */
    public static ProblemException txServerNotRegistered(String url, nzi.fhir.validator.core.enums.SupportedFhirVersion version) {
        return new ProblemException(400, TYPE_TX_SERVER_NOT_REGISTERED, "Terminology server " + url
                + " is not listed as an open server for " + version + " by the official HL7 terminology registry")
                .withExtension("registry", TX_REGISTRY)
                .withExtension("fhirVersion", version.name());
    }

    /** Spec 007 FR-012a: the registry's list has never been obtained and the server isn't built in. */
    public static ProblemException txRegistryUnavailable(nzi.fhir.validator.core.enums.SupportedFhirVersion version) {
        return new ProblemException(503, TYPE_TX_REGISTRY_UNAVAILABLE, "The list of terminology servers for " + version
                + " has not been obtained from the official HL7 terminology registry yet; retry later, or use mode none")
                .withExtension("registry", TX_REGISTRY)
                .withHeader("Retry-After", "60");
    }

    /** The {@code ig} package of a validation request is not stored (spec 004 FR-003). The name is client input. */
    public static ProblemException igNotStored(String label) {
        return new ProblemException(404, TYPE_PACKAGE_NOT_FOUND, "IG package " + label
                + " is not stored. Register or upload it first via /igs/register or /igs/upload");
    }

    /** A stored package that isn't stored, or that the caller may not see (spec 005: the same answer). */
    public static ProblemException packageNotStored(String key) {
        return new ProblemException(404, TYPE_PACKAGE_NOT_FOUND, "IG package " + key + " is not stored");
    }

    /** A transfer of ownership by a caller that isn't the system organisation (spec 005 FR-019). */
    public static ProblemException transferForbidden() {
        return new ProblemException(403, "forbidden", "Only the system organisation can transfer ownership");
    }

    /**
     * A dependency of the chosen IG package is not stored, so its validator context cannot be built (spec 004 FR-017).
     */
    public static ProblemException dependencyNotFound(String missing) {
        return new ProblemException(424, TYPE_DEPENDENCY_NOT_FOUND, "A dependency of the requested IG package is not stored: "
                + missing + ". Register or upload it via /igs/register or /igs/upload");
    }

    /**
     * A dependency of the chosen IG package exists but isn't visible to the caller (spec 005 FR-012, research R5). The
     * text names no package, so the refusal reveals nothing about content the caller may not use.
     */
    public static ProblemException dependencyUnavailable() {
        return new ProblemException(424, TYPE_DEPENDENCY_NOT_FOUND, "A dependency of the requested IG package is not "
                + "available. Register or upload it via /igs/register or /igs/upload");
    }

    /** Upload or download of a {@code name#version} another organisation stored (spec 005 FR-017, research R7). */
    public static ProblemException conflictOwnedElsewhere(String key) {
        return new ProblemException(409, TYPE_CONFLICT, "IG package " + key + " is already stored by another organisation");
    }

    public ProblemException withExtension(String name, Object value) {
        extensions.put(name, value);
        return this;
    }

    public ProblemException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String detail() {
        return detail;
    }

    public JsonObject extensions() {
        return extensions;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
