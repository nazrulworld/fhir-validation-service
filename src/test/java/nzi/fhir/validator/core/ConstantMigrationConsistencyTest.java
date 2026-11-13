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
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V15__baseline_1_0.sql")) {
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

    @Test
    void parsedFormsMatchTheStrings() {
        assertTrue(Constant.SYSTEM_ORG_UUID.toString().equals(Constant.SYSTEM_ORG_ID));
        assertTrue(Constant.TRIAL_ORG_UUID.toString().equals(Constant.TRIAL_ORG_ID));
        assertTrue(Constant.SYS_ORG_API_KEY_EXPIRES_AT.toString().equals(Constant.SYS_ORG_API_KEY_EXPIRES));
    }
}
