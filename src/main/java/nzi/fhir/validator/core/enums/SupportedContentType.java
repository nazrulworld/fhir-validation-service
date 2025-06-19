package nzi.fhir.validator.core.enums;

/**
 * Enum representing the supported content types.
 * @author Md Nazrul Islam
 */
public enum SupportedContentType {
    JSON("application/json"),
    XML("application/xml"),
    FHIR_JSON("application/fhir+json"),
    FHIR_XML("application/fhir+xml");

    private final String mimeType;

    SupportedContentType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }

    // Optional: Method to get enum by mimeType
    public static SupportedContentType fromMimeType(String mimeType) {
        for (SupportedContentType type : values()) {
            if (type.mimeType.equalsIgnoreCase(mimeType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported content type: " + mimeType);

    }
}