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
