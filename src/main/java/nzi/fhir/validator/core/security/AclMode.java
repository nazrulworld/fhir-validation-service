package nzi.fhir.validator.core.security;

/**
 * Whether API-key access control is enforced (spec 009): {@code FV_GLOBAL_DISABLED_ACL_SECURITY}. Stored per start in
 * {@code service_settings.acl_security} as {@link #value()}.
 */
public enum AclMode {
    /** Keys are required (the default, as in 4.1). */
    ENABLED,
    /** No endpoint needs a key; requests run as the fixed callers. */
    DISABLED;

    /** The stored form: {@code enabled} or {@code disabled}. */
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The mode stored as {@code value}. */
    public static AclMode fromValue(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
