package nzi.fhir.validator.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The well-known values exist twice: in {@link Constant} and as SQL literals in the 1.0.0 baseline migration (spec 008,
 * research R16). Applied migrations are never edited, so the code must follow the migration.
 */
class ConstantMigrationConsistencyTest {

    private String baseline() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V18__baseline_1_0.sql")) {
            assertNotNull(in, "the baseline is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The well-known organisations are seeded with the constant values. */
    @Test
    void baselineSeedsTheWellKnownOrganisations() throws IOException {
        String sql = baseline();
        for (String value : List.of(Constant.SYSTEM_ORG_ID, Constant.SYSTEM_ORG_SHORT_NAME, Constant.SYSTEM_ORG_LONG_NAME,
                Constant.SYSTEM_ORG_TYPE, Constant.TRIAL_ORG_ID, Constant.TRIAL_ORG_SHORT_NAME, Constant.TRIAL_ORG_LONG_NAME,
                Constant.TRIAL_ORG_TYPE)) {
            assertTrue(sql.contains("'" + value + "'"), "the baseline contains '" + value + "'");
        }
    }

    /** The revocation trigger lists the well-known key ids (the keys themselves are installed at startup). */
    @Test
    void baselineTriggerUsesTheWellKnownKeyIds() throws IOException {
        String sql = baseline();
        for (String id : List.of(Constant.SYS_ORG_ADMIN_API_KEY_ID, Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID)) {
            assertTrue(sql.contains("'" + id + "'"), "the baseline contains '" + id + "'");
        }
    }

    /**
     * Spec 014 (FR-015): the baseline seeds the initial caps with the constant values and ids. The trial validator key's
     * cap is not in the baseline: the key is installed at startup (ApiKeyRepository.upsertWellKnown).
     */
    @Test
    void baselineSeedsTheInitialCaps() throws IOException {
        String sql = baseline();
        String settings = nzi.fhir.validator.core.settings.ServiceSettingsRepository.THROTTLE_DEFAULT_KEY;
        String orgSettings = nzi.fhir.validator.core.settings.ServiceSettingsRepository.THROTTLE_DEFAULT_ORG;
        assertTrue(sql.contains("('" + settings + "', '" + Constant.THROTTLE_DEFAULT_KEY_PER_MINUTE + "'"), "default key cap");
        assertTrue(sql.contains("('" + orgSettings + "', '" + Constant.THROTTLE_DEFAULT_ORG_PER_MINUTE + "'"), "default org cap");
        assertTrue(sql.contains("('" + Constant.TRIAL_ORG_ID + "', '" + Constant.TRIAL_ORG_SHORT_NAME + "', '"
                + Constant.TRIAL_ORG_LONG_NAME + "', '" + Constant.TRIAL_ORG_TYPE + "', true, "
                + Constant.TRIAL_ORG_RATE_LIMIT_PER_MINUTE + ")"), "trial organisation cap");
        for (String literal : List.of("BETWEEN 1 AND " + Constant.RATE_LIMIT_MAX_PER_MINUTE,
                "value::int <= " + Constant.RATE_LIMIT_MAX_PER_MINUTE)) {
            assertTrue(sql.contains(literal), "the baseline contains " + literal);
        }
    }

    @Test
    void parsedFormsMatchTheStrings() {
        assertTrue(Constant.SYSTEM_ORG_UUID.toString().equals(Constant.SYSTEM_ORG_ID));
        assertTrue(Constant.TRIAL_ORG_UUID.toString().equals(Constant.TRIAL_ORG_ID));
        assertTrue(Constant.SYS_ORG_API_KEY_EXPIRES_AT.toString().equals(Constant.SYS_ORG_API_KEY_EXPIRES));
    }
}
