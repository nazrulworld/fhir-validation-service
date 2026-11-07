package nzi.fhir.validator.core.org;

import java.util.UUID;

/**
 * Constants of the organisation model (spec 005, research R2).
 */
public final class Organisations {
    /** The id of the system organisation, fixed so migrations, column defaults and code need no lookup. */
    public static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** The system organisation's short name until {@code FV_SYSTEM_ORG_NAME_SHORT} changes it. */
    public static final String DEFAULT_SYSTEM_SHORT_NAME = "system";

    public enum Type { SYSTEM, COMMERCIAL, NON_COMMERCIAL }

    private Organisations() {
    }
}
