package nzi.fhir.validator.core;

import java.time.Instant;
import java.util.UUID;

/**
 * Well-known identifiers of the service (spec 008, research R16): the single source of truth for the system and trial
 * organisations and the keys installed from configuration. The migration {@code V11__well_known_organisations.sql}
 * repeats these values as SQL literals; {@code ConstantMigrationConsistencyTest} keeps the two in step.
 *
 * @author Md Nazrul Islam
 */
public final class Constant {

    // The system organisation: owns everything not owned by another organisation (spec 005).
    public final static String SYSTEM_ORG_ID = "00000000-0000-0000-0000-100000000001";
    public final static String SYSTEM_ORG_SHORT_NAME = "system";
    public final static String SYSTEM_ORG_LONG_NAME = "FHIR Validation Service";
    public final static String SYSTEM_ORG_TYPE = "SYSTEM";
    public final static UUID SYSTEM_ORG_UUID = UUID.fromString(SYSTEM_ORG_ID);

    // The trial organisation: owner of the shared trial validator key.
    public final static String TRIAL_ORG_ID = "00000000-0000-0000-0000-100000000002";
    public final static String TRIAL_ORG_SHORT_NAME = "trial";
    public final static String TRIAL_ORG_LONG_NAME = "Free trial validation service";
    public final static String TRIAL_ORG_TYPE = "NON_COMMERCIAL";
    public final static UUID TRIAL_ORG_UUID = UUID.fromString(TRIAL_ORG_ID);

    // Expiry of the keys installed from configuration (far future; they are rotated through configuration).
    public final static String SYS_ORG_API_KEY_EXPIRES = "2099-12-30T23:59:59Z";
    public final static Instant SYS_ORG_API_KEY_EXPIRES_AT = Instant.parse(SYS_ORG_API_KEY_EXPIRES);

    // The system administrator key, installed from FV_ADMIN_API_KEY.
    public final static String SYS_ORG_ADMIN_API_KEY_ID = "00000000-0000-0000-0000-200000000001";
    public final static String SYS_ORG_ADMIN_API_KEY_ROLE = "ADMIN";
    public final static String SYS_ORG_ADMIN_API_KEY_NAME = "system administrator";
    public final static UUID SYS_ORG_ADMIN_API_KEY_UUID = UUID.fromString(SYS_ORG_ADMIN_API_KEY_ID);

    // The trial validator key, installed from FV_VALIDATOR_API_KEY and owned by the trial organisation.
    public final static String TRIAL_ORG_VALIDATOR_API_KEY_ID = "00000000-0000-0000-0000-200000000002";
    public final static String TRIAL_ORG_VALIDATOR_API_KEY_ROLE = "VALIDATE";
    public final static String TRIAL_ORG_VALIDATOR_API_KEY_NAME = "trial validator";
    public final static UUID TRIAL_ORG_VALIDATOR_API_KEY_UUID = UUID.fromString(TRIAL_ORG_VALIDATOR_API_KEY_ID);

    // Spec 014 (FR-015, clarification Q3): the caps installed by V18. The migration repeats them as SQL literals;
    // ConstantMigrationConsistencyTest keeps the two in step. Operators change them with 'limit set'.
    public final static int THROTTLE_DEFAULT_KEY_PER_MINUTE = 600;
    public final static int THROTTLE_DEFAULT_ORG_PER_MINUTE = 1200;
    public final static int TRIAL_ORG_RATE_LIMIT_PER_MINUTE = 60;
    public final static int TRIAL_ORG_VALIDATOR_API_KEY_RATE_LIMIT_PER_MINUTE = 30;
    /** Spec 014 FR-012: the largest cap (validation requests per minute) that may be stored. */
    public final static int RATE_LIMIT_MAX_PER_MINUTE = 1_000_000;

    private Constant() {
    }
}
