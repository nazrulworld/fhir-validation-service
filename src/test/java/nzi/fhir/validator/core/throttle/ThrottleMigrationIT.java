package nzi.fhir.validator.core.throttle;

import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 014 (T004, research R6/R7, data-model §1–§2): the 1.0.0 baseline has the cap columns, constraints and the
 * UNLOGGED count tables, and seeds the initial caps. (Upgrades from development-build schemas: SquashedBaselineIT.)
 */
class ThrottleMigrationIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema.";
    private static final String OTHER_KEY = "6c1f6b3e-0000-4000-8000-000000000001";
    private static final String OTHER_ORG = "6c1f6b3e-0000-4000-8000-0000000000a1";

    @BeforeEach
    void reset() {
        resetSchema();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
    }

    private static void migrate() {
        DatabaseService.flywayBuilder(connectOptions()).load().migrate();
    }

    private static Object scalar(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** (a) the empty-database path: columns, seeds, UNLOGGED tables. */
    @Test
    void emptyDatabase() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            for (String table : new String[]{"api_keys", "organisations"}) {
                assertEquals("YES|integer", scalar(c, "SELECT is_nullable || '|' || data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'fhir_validator_schema' AND table_name = ? AND column_name = 'rate_limit_per_minute'",
                        table), table);
            }
            assertEquals(String.valueOf(Constant.THROTTLE_DEFAULT_KEY_PER_MINUTE), scalar(c,
                    "SELECT value FROM " + S + "service_settings WHERE name = 'throttle_default_key_per_minute'"));
            assertEquals(String.valueOf(Constant.THROTTLE_DEFAULT_ORG_PER_MINUTE), scalar(c,
                    "SELECT value FROM " + S + "service_settings WHERE name = 'throttle_default_org_per_minute'"));
            assertEquals(Constant.TRIAL_ORG_RATE_LIMIT_PER_MINUTE, scalar(c,
                    "SELECT rate_limit_per_minute FROM " + S + "organisations WHERE id = ?::uuid", Constant.TRIAL_ORG_ID));
            assertNull(scalar(c, "SELECT rate_limit_per_minute FROM " + S + "organisations WHERE id = ?::uuid", Constant.SYSTEM_ORG_ID));
            for (String table : new String[]{"throttle_counts", "throttle_instances"}) {
                assertEquals("u", String.valueOf(scalar(c, "SELECT c.relpersistence FROM pg_class c JOIN pg_namespace n "
                        + "ON n.oid = c.relnamespace WHERE n.nspname = 'fhir_validator_schema' AND c.relname = ?", table)), table);
            }
        }
    }

    /** (c) the constraints: bounds, the system organisation, the setting values. */
    @Test
    void constraintsReject() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            exec(c, "INSERT INTO " + S + "organisations (id, name_short, name_long, type) VALUES ('" + OTHER_ORG
                    + "', 'acme', 'Acme', 'COMMERCIAL')");
            exec(c, "INSERT INTO " + S + "api_keys (id, name, key_hash, role, owner) VALUES ('" + OTHER_KEY
                    + "', 'acme-a', '\\x02', 'VALIDATE', '" + OTHER_ORG + "'), ('6c1f6b3e-0000-4000-8000-000000000002', "
                    + "'sys-v', '\\x03', 'VALIDATE', '" + Constant.SYSTEM_ORG_ID + "')");
            for (String bad : new String[]{"0", "1000001"}) {
                assertThrows(SQLException.class, () -> exec(c, "UPDATE " + S + "api_keys SET rate_limit_per_minute = "
                        + bad + " WHERE id = '" + OTHER_KEY + "'"), "key " + bad);
                assertThrows(SQLException.class, () -> exec(c, "UPDATE " + S + "organisations SET rate_limit_per_minute = "
                        + bad + " WHERE id = '" + OTHER_ORG + "'"), "org " + bad);
            }
            exec(c, "UPDATE " + S + "api_keys SET rate_limit_per_minute = 1000000 WHERE id = '" + OTHER_KEY + "'");
            assertThrows(SQLException.class, () -> exec(c, "UPDATE " + S + "organisations SET rate_limit_per_minute = 5 "
                    + "WHERE id = '" + Constant.SYSTEM_ORG_ID + "'"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE " + S + "api_keys SET rate_limit_per_minute = 5 "
                    + "WHERE owner = '" + Constant.SYSTEM_ORG_ID + "'"));
            for (String bad : new String[]{"0", "abc", "1000001"}) {
                assertThrows(SQLException.class, () -> exec(c, "UPDATE " + S + "service_settings SET value = '" + bad
                        + "' WHERE name = 'throttle_default_key_per_minute'"), bad);
            }
        }
    }

    /** T011: the cap snapshot of a fresh database (read through the Vert.x client, as the service does). */
    @Test
    void capsSnapshotOfAFreshDatabase() throws Exception {
        migrate();
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        io.vertx.sqlclient.Pool pool = createPgPool(vertx);
        try {
            ThrottleCaps caps = ThrottleCapsRepository.load(pool).toCompletionStage().toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.OptionalInt.of(Constant.THROTTLE_DEFAULT_KEY_PER_MINUTE), caps.defaultKey());
            assertEquals(java.util.OptionalInt.of(Constant.THROTTLE_DEFAULT_ORG_PER_MINUTE), caps.defaultOrg());
            assertEquals(java.util.Map.of(Constant.TRIAL_ORG_UUID, Constant.TRIAL_ORG_RATE_LIMIT_PER_MINUTE), caps.orgCaps());
            assertEquals(java.util.Map.of(), caps.keyCaps(), "no key is installed by the migrations alone");
        } finally {
            pool.close();
            vertx.close();
        }
    }

    private static Object trialKeyCap() throws SQLException {
        try (Connection c = connect()) {
            return scalar(c, "SELECT rate_limit_per_minute FROM " + S + "api_keys WHERE id = ?::uuid",
                    Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID);
        }
    }

    private static void startAndStop(String validatorKey) throws Exception {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        try {
            io.vertx.core.json.JsonObject config = nzi.fhir.validator.testsupport.AppHarness.baseConfig(getPostgresHost(),
                    getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
            config.getJsonObject("auth").put("validatorKey", validatorKey);
            nzi.fhir.validator.testsupport.AppHarness app = nzi.fhir.validator.testsupport.AppHarness.start(vertx, config)
                    .toCompletionStage().toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
            app.awaitReady(90_000).toCompletionStage().toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
            app.stop().toCompletionStage().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** T026 (FR-015, US3 AC2): the trial key gets 30 when first installed; an operator's change survives restarts. */
    @Test
    void trialKeyCapIsSeededOnceAndKeptAfterwards() throws Exception {
        startAndStop(nzi.fhir.validator.testsupport.AppHarness.VALIDATOR_KEY);
        assertEquals(Constant.TRIAL_ORG_VALIDATOR_API_KEY_RATE_LIMIT_PER_MINUTE, trialKeyCap(), "seeded on first install");
        try (Connection c = connect()) {
            exec(c, "UPDATE " + S + "api_keys SET rate_limit_per_minute = NULL WHERE id = '"
                    + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'");
        }
        startAndStop(nzi.fhir.validator.testsupport.AppHarness.VALIDATOR_KEY);
        assertNull(trialKeyCap(), "a removed cap stays removed after a restart");
        startAndStop("rotated-validator-key-0123456789abcdefghij");
        assertNull(trialKeyCap(), "and after a key rotation");
        try (Connection c = connect()) {
            exec(c, "UPDATE " + S + "api_keys SET rate_limit_per_minute = 45 WHERE id = '"
                    + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'");
        }
        startAndStop("rotated-validator-key-0123456789abcdefghij");
        assertEquals(45, trialKeyCap(), "a changed cap is kept");
    }

    /** (d) research D1: the CASE-guarded constraint never casts the other settings' values. */
    @Test
    void otherSettingsStillWork() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            for (String[] kv : new String[][]{{"acl_security", "enabled"}, {"acl_security", "disabled"}, {"fhir_versions", "R4,R5"}}) {
                exec(c, "INSERT INTO " + S + "service_settings (name, value) VALUES ('" + kv[0] + "', '" + kv[1]
                        + "') ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value");
                assertEquals(kv[1], scalar(c, "SELECT value FROM " + S + "service_settings WHERE name = ?", kv[0]));
            }
        }
    }
}
