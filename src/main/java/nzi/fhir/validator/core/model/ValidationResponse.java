package nzi.fhir.validator.core.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * The service's JSON validation result ({@code application/json}): {@code {valid, messages[{severity, location,
 * message}], fhirVersion, appliedProfiles, igPackage}}. {@code fhirVersion} and {@code appliedProfiles} were added in
 * 1.0.0; {@code igPackage} (the IG package the result came from, {@code null} for the core-only base context) in 3.0.0.
 *
 * @author Md Nazrul Islam
 */
public class ValidationResponse {
    private final boolean valid;
    private final JsonArray messages;
    private final String fhirVersion;
    private final List<String> appliedProfiles;
    private final String igPackage;

    public ValidationResponse(boolean valid, JsonArray messages, String fhirVersion, List<String> appliedProfiles) {
        this(valid, messages, fhirVersion, appliedProfiles, null);
    }

    public ValidationResponse(boolean valid, JsonArray messages, String fhirVersion, List<String> appliedProfiles,
                              String igPackage) {
        this.valid = valid;
        this.messages = messages;
        this.fhirVersion = fhirVersion;
        this.appliedProfiles = List.copyOf(appliedProfiles);
        this.igPackage = igPackage;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("valid", valid)
                .put("messages", messages)
                .put("fhirVersion", fhirVersion)
                .put("appliedProfiles", new JsonArray(appliedProfiles))
                .put("igPackage", igPackage);
    }

    public boolean isValid() { return valid; }
    public JsonArray getMessages() { return messages; }
    public String getFhirVersion() { return fhirVersion; }
    public List<String> getAppliedProfiles() { return appliedProfiles; }
    public String getIgPackage() { return igPackage; }
}
