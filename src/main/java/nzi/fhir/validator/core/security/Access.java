package nzi.fhir.validator.core.security;

import nzi.fhir.validator.core.org.Organisations;

import java.util.UUID;

/**
 * Owner and sharing of an IG package or a validator context (spec 005, data-model §2).
 *
 * @param ownerShortName for responses and logs; the UUID decides access
 * @param shareable      every caller may use it; otherwise only the owner and the system organisation
 */
public record Access(UUID owner, String ownerShortName, boolean shareable) {

    /** Owned by the system organisation and shareable: the base context, and rows written before 3.1. */
    public static Access systemShared(String systemShortName) {
        return new Access(Organisations.SYSTEM_ID, systemShortName, true);
    }
}
