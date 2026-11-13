package nzi.fhir.validator.core.org;

import nzi.fhir.validator.core.Constant;

import java.util.UUID;

/**
 * Constants of the organisation model (spec 005, research R2).
 */
public final class Organisations {
    /**
     * The id of the system organisation, fixed so migrations, column defaults and code need no lookup. Defined in
     * {@link Constant} (spec 008: {@code …-100000000001}, migration V11).
     */
    public static final UUID SYSTEM_ID = Constant.SYSTEM_ORG_UUID;

    /** The system organisation's short name until {@code FV_SYSTEM_ORG_NAME_SHORT} changes it. */
    public static final String DEFAULT_SYSTEM_SHORT_NAME = Constant.SYSTEM_ORG_SHORT_NAME;

    /** The id of the trial organisation (spec 008), owner of the trial validator key. */
    public static final UUID TRIAL_ID = Constant.TRIAL_ORG_UUID;

    public enum Type { SYSTEM, COMMERCIAL, NON_COMMERCIAL }

    private Organisations() {
    }
}
