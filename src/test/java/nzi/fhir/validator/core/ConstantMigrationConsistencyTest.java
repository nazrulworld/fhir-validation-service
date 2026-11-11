package nzi.fhir.validator.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The well-known values exist twice: in {@link Constant} and as SQL literals in V11 (spec 008, research R16). Applied
 * migrations are never edited, so the code must follow the migration.
 */
class ConstantMigrationConsistencyTest {

    @Test
    void v11UsesTheConstantValues() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V11__well_known_organisations.sql")) {
            assertNotNull(in, "V11 is on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String value : List.of(Constant.SYSTEM_ORG_ID, Constant.SYSTEM_ORG_SHORT_NAME, Constant.SYSTEM_ORG_LONG_NAME,
                Constant.SYSTEM_ORG_TYPE, Constant.TRIAL_ORG_ID, Constant.TRIAL_ORG_SHORT_NAME, Constant.TRIAL_ORG_LONG_NAME,
                Constant.TRIAL_ORG_TYPE, Constant.SYS_ORG_ADMIN_API_KEY_ID, Constant.SYS_ORG_ADMIN_API_KEY_NAME)) {
            assertTrue(sql.contains("'" + value + "'"), "V11 contains '" + value + "'");
        }
    }

    /** V13 lists the well-known key ids in its trigger. */
    @Test
    void v13UsesTheWellKnownKeyIds() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V13__revoked_well_known_key_hashes.sql")) {
            assertNotNull(in, "V13 is on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String id : List.of(Constant.SYS_ORG_ADMIN_API_KEY_ID, Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID)) {
            assertTrue(sql.contains("'" + id + "'"), "V13 contains '" + id + "'");
        }
    }

    @Test
    void parsedFormsMatchTheStrings() {
        assertTrue(Constant.SYSTEM_ORG_UUID.toString().equals(Constant.SYSTEM_ORG_ID));
        assertTrue(Constant.TRIAL_ORG_UUID.toString().equals(Constant.TRIAL_ORG_ID));
        assertTrue(Constant.SYS_ORG_API_KEY_EXPIRES_AT.toString().equals(Constant.SYS_ORG_API_KEY_EXPIRES));
    }
}
