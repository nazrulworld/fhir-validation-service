package nzi.fhir.validator.core.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * The service's JSON validation result ({@code application/json}): {@code {valid, messages[{severity, location,
 * message}], fhirVersion, appliedProfiles, igPackage, igOwner, igShareable}}.
 * {@code igPackage} is the IG package the result came from ({@code null} for the core-only base context);
 * {@code igOwner} and {@code igShareable} are the context's owner and sharing.
 *
 * @author Md Nazrul Islam
 */
public class ValidationResponse {
    private final boolean valid;
    private final JsonArray messages;
    private final String fhirVersion;
    private final List<String> appliedProfiles;
    private final String igPackage;
    private final String igOwner;
    private final Boolean igShareable;

    public ValidationResponse(boolean valid, JsonArray messages, String fhirVersion, List<String> appliedProfiles) {
        this(valid, messages, fhirVersion, appliedProfiles, null);
    }

    public ValidationResponse(boolean valid, JsonArray messages, String fhirVersion, List<String> appliedProfiles,
                              String igPackage) {
        this(valid, messages, fhirVersion, appliedProfiles, igPackage, null, null);
    }

    /**
     * @param igOwner     short name of the organisation owning the context
     * @param igShareable whether the context is shareable
     */
    public ValidationResponse(boolean valid, JsonArray messages, String fhirVersion, List<String> appliedProfiles,
                              String igPackage, String igOwner, Boolean igShareable) {
        this.igOwner = igOwner;
        this.igShareable = igShareable;
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
                .put("igPackage", igPackage)
                .put("igOwner", igOwner)
                .put("igShareable", igShareable);
    }

    public boolean isValid() { return valid; }
    public JsonArray getMessages() { return messages; }
    public String getFhirVersion() { return fhirVersion; }
    public List<String> getAppliedProfiles() { return appliedProfiles; }
    public String getIgPackage() { return igPackage; }
    public String getIgOwner() { return igOwner; }
    public Boolean getIgShareable() { return igShareable; }
}
