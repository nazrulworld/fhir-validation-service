package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.Caller;

import java.util.UUID;

/**
 * Who owns what a store writes (spec 005 FR-016, research R7): every package newly stored by one call, downloaded
 * dependencies included, gets the caller's organisation and the requested sharing. A package another organisation
 * stored can be replaced only by the system organisation.
 *
 * @param callerIsSystem the caller may replace a package whatever its owner
 */
public record PackageOwnership(UUID owner, boolean shareable, boolean callerIsSystem) {

    /** System-owned and shareable: internal stores and the 3.0 behaviour. */
    public static final PackageOwnership SYSTEM = new PackageOwnership(Organisations.SYSTEM_ID, true, true);

    /**
     * @throws IllegalArgumentException for an anonymous caller, who may never store anything
     */
    public static PackageOwnership of(Caller caller, boolean shareable) {
        if (caller.anonymous() || caller.orgId() == null) {
            throw new IllegalArgumentException("Anonymous callers can't store IG packages");
        }
        return new PackageOwnership(caller.orgId(), shareable, caller.system());
    }
}
