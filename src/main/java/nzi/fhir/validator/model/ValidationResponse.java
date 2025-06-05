package nzi.fhir.validator.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * @author Md Nazrul Islam
 */
public class ValidationResponse {
    private final boolean valid;
    private final JsonArray messages;
    private final String resourceType;
    private final String profile;

    public ValidationResponse(boolean valid, JsonArray messages,
                              String resourceType, String profile) {
        this.valid = valid;
        this.messages = messages;
        this.resourceType = resourceType;
        this.profile = profile;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("valid", valid)
                .put("messages", messages)
                .put("resourceType", resourceType)
                .put("profile", profile);
    }

    // Static factory method
    public static ValidationResponse fromFhirResult(boolean valid,
                                                    JsonArray messages,
                                                    String resourceType,
                                                    String profileUrl) {
        return new ValidationResponse(valid, messages, resourceType, profileUrl);
    }

    // Getters
    public boolean isValid() { return valid; }
    public JsonArray getMessages() { return messages; }
    public String getResourceType() { return resourceType; }
    public String getProfile() { return profile; }
}
