package nzi.fhir.validator.core.security;

import nzi.fhir.validator.core.org.Organisations;

import java.util.UUID;

/**
 * Who is making a request (spec 005, research R3): the organisation of the API key, or anonymous. Put into the
 * routing context by {@link ApiKeyAuthHandler} and passed explicitly to every service call that checks access, so no
 * path can run with more rights than its caller.
 *
 * @param orgId        the key's organisation, {@code null} for anonymous callers
 * @param orgShortName the organisation's short name (logs only), {@code null} for anonymous callers
 * @param role         the key's role, {@code null} for anonymous callers
 * @param system       the caller acts for the system organisation (sees and may change everything)
 */
public record Caller(UUID orgId, String orgShortName, ApiKeyRepository.Role role, boolean system, boolean anonymous) {

    /** A request without an API key: may use shareable content only. */
    public static final Caller ANONYMOUS = new Caller(null, null, null, false, true);

    /** A key of organisation {@code orgId}. */
    public static Caller of(UUID orgId, String orgShortName, ApiKeyRepository.Role role) {
        return new Caller(orgId, orgShortName, role, Organisations.SYSTEM_ID.equals(orgId), false);
    }

    /**
     * The system organisation acting as an administrator: the command line (an operator with database access is
     * trusted), and tests that need full access. Never a default for requests.
     */
    public static Caller system(String shortName) {
        return new Caller(Organisations.SYSTEM_ID, shortName, ApiKeyRepository.Role.ADMIN, true, false);
    }

    /** Whether {@code owner} is this caller's organisation. */
    public boolean owns(UUID owner) {
        return !anonymous && orgId != null && orgId.equals(owner);
    }

    /** For logs: the short name, or {@code anonymous}. */
    public String label() {
        return anonymous ? "anonymous" : orgShortName;
    }
}
