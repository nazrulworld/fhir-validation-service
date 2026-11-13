package nzi.fhir.validator.core.service;

import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 1.0.0 baseline (V15) applies cleanly to an empty database and enforces the schema's rules, even against
 * hand-written SQL. Databases with an earlier migration history are covered by SquashedBaselineIT.
 */
class MigrationUpgradeIT extends BaseTestContainer {

    private static final String S = "fhir_validator_schema";

    private PgConnectOptions freshDatabase() throws SQLException {
        String name = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return new PgConnectOptions()
                .setHost(getPostgresHost()).setPort(getPostgresPort())
                .setDatabase(name).setUser(POSTGRES_USERNAME).setPassword(POSTGRES_PASSWORD);
    }

    private static Connection jdbc(PgConnectOptions o) throws SQLException {
        return DriverManager.getConnection(DatabaseService.jdbcUrl(o), o.getUser(), o.getPassword());
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void assertLatestSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            String version64 = "v" + "1".repeat(63);
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw) "
                    + "VALUES ('long.version.pkg', '" + version64 + "', '{}', '\\x00')");
            try (ResultSet rs = st.executeQuery("SELECT source FROM " + S + ".fhir_implementation_guides WHERE ig_package_id='long.version.pkg'")) {
                assertTrue(rs.next());
                assertEquals("UNKNOWN", rs.getString(1));
            }
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".fhir_implementation_guides "
                    + "(ig_package_id, ig_package_version, ig_package_meta, content_raw, source) VALUES ('bad.source', '1.0.0', '{}', '\\x00', 'FTP')"));
        }
        assertEquals(List.of("R4", "R4B", "R5", "STU3"),
                column(c, "SELECT fhir_version FROM " + S + ".fhir_validator_logs WHERE validator_id='standard_validator' ORDER BY fhir_version"));
        assertEquals(List.of("1"), column(c, "SELECT DISTINCT revision::text FROM " + S + ".fhir_validator_logs WHERE included_ig_packages = '{}'"));
        assertEquals(List.of(), column(c, "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='" + S + "' AND table_name='fhir_profiles'"), "V6 drops fhir_profiles");
        assertEquals(List.of(), column(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='" + S + "' AND table_name='fhir_validator_logs' AND column_name='included_profiles'"),
                "V6 drops fhir_validator_logs.included_profiles");
        assertEquals(List.of(), column(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='" + S + "' AND table_name='fhir_validator_logs' AND column_name='resolved_ig_packages'"),
                "V7 drops fhir_validator_logs.resolved_ig_packages (resolved per IG at apply time, spec 004)");
        assertEquals(List.of("1|1"), column(c, "SELECT id || '|' || revision FROM " + S + ".fhir_package_catalog"),
                "V7 seeds the single package catalog revision row");
        try (Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S
                    + ".fhir_package_catalog (id, revision) VALUES (2, 1)"), "the catalog revision is a single row");
        }
    }

    private static void assertApiKeysTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('k', '\\x01', 'ADMIN')");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('k2', '\\x02', 'ROOT')"));
        }
        // V10 (spec 008): optional expiry and the last-used client address.
        assertEquals(List.of("expires_at|YES|timestamp with time zone", "last_used_ip|YES|inet"),
                column(c, "SELECT column_name || '|' || is_nullable || '|' || data_type FROM information_schema.columns "
                        + "WHERE table_schema='" + S + "' AND table_name='api_keys' AND column_name IN ('expires_at', 'last_used_ip') "
                        + "ORDER BY column_name"));
    }

    @Test
    void appliesToEmptyDatabase() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db)) {
            assertLatestSchema(c);
            assertApiKeysTable(c);
        }
    }

    private static final String SYSTEM_ID = "00000000-0000-0000-0000-100000000001";
    private static final String TRIAL_ID = "00000000-0000-0000-0000-100000000002";
    private static final String ADMIN_KEY_ID = "00000000-0000-0000-0000-200000000001";
    private static final String TRIAL_KEY_ID = "00000000-0000-0000-0000-200000000002";

    private PgConnectOptions migrated() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        return db;
    }

    // --- Organisations (spec 005, spec 008) ----------------------------------------------------------------------

    /** The well-known system and trial organisations are seeded with their well-known ids. */
    @Test
    void seedsTheWellKnownOrganisations() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of(SYSTEM_ID + "|system|SYSTEM|true", TRIAL_ID + "|trial|NON_COMMERCIAL|true"),
                    column(c, "SELECT id || '|' || name_short || '|' || type || '|' || is_active FROM " + S
                            + ".organisations ORDER BY id"));
            assertEquals(List.of("Free trial validation service"), column(c, "SELECT name_long FROM " + S
                    + ".organisations WHERE id = '" + TRIAL_ID + "'"));
        }
    }

    /** The system organisation is protected by constraints, even against hand-written SQL (FR-003). */
    @Test
    void protectsTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type) "
                    + "VALUES ('other-system', 'Other', 'SYSTEM')"), "a second SYSTEM organisation");
            assertThrows(SQLException.class, () -> st.execute("UPDATE " + S + ".organisations SET is_active = false "
                    + "WHERE id = '" + SYSTEM_ID + "'"), "deactivating the system organisation");
            assertThrows(SQLException.class, () -> st.execute("UPDATE " + S + ".organisations SET type = 'COMMERCIAL' "
                    + "WHERE id = '" + SYSTEM_ID + "'"), "demoting the system organisation");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x05', 'ADMIN')");
            assertThrows(SQLException.class, () -> st.execute("DELETE FROM " + S + ".organisations WHERE id = '" + SYSTEM_ID + "'"),
                    "an organisation that still owns a key can't be deleted (FR-005, ON DELETE RESTRICT)");
        }
    }

    /** Short names are unique regardless of case, and names and countries follow their patterns. */
    @Test
    void shortNamesAreUniqueIgnoringCase() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type) VALUES ('acme', 'Acme', 'COMMERCIAL')");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type) "
                    + "VALUES ('ACME', 'Acme again', 'NON_COMMERCIAL')"));
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type) "
                    + "VALUES ('a', 'Too short', 'COMMERCIAL')"), "name_short pattern");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type, country) "
                    + "VALUES ('beta', 'Beta', 'COMMERCIAL', 'dk')"), "country pattern");
        }
    }

    /** Rows without an explicit owner belong to the system organisation; owner references are ON DELETE RESTRICT. */
    @Test
    void ownersDefaultToTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            for (String table : List.of("api_keys", "fhir_implementation_guides", "fhir_validator_snapshots")) {
                List<String> dflt = column(c, "SELECT column_default FROM information_schema.columns WHERE table_schema='" + S
                        + "' AND table_name='" + table + "' AND column_name='owner'");
                assertTrue(dflt.get(0).contains("100000000001"), table + " default: " + dflt);
                assertEquals(List.of("r"), column(c, "SELECT confdeltype::text FROM pg_constraint WHERE conname = '" + table + "_owner_fkey'"),
                        table + "_owner_fkey is ON DELETE RESTRICT");
            }
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('new', '\\x07', 'ADMIN')");
            assertEquals(List.of(SYSTEM_ID), column(c, "SELECT owner::text FROM " + S + ".api_keys WHERE name = 'new'"));
        }
    }

    // --- Terminology (spec 007) -----------------------------------------------------------------------------------

    /** The terminology columns and their constraint, and the registry snapshot table. */
    @Test
    void terminologyColumnsAndRegistrySnapshot() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package) VALUES ('R4', 'a.ig#1.0.0')");
            assertEquals(List.of("NONE|null"), column(c, "SELECT tx_mode || '|' || coalesce(tx_server, 'null') FROM " + S
                    + ".fhir_validator_snapshots"), "FR-005: a context without a setting is in mode NONE");
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package, tx_mode, tx_server) "
                    + "VALUES ('R4', 'b.ig#1.0.0', 'SERVER', 'http://tx.fhir.org/r4'), ('R4', 'c.ig#1.0.0', 'ECOSYSTEM', NULL)");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".fhir_validator_snapshots "
                    + "(fhir_version, ig_package, tx_mode, tx_server) VALUES ('R4', 'd.ig#1.0.0', 'SERVER', NULL)"),
                    "SERVER needs a server");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".fhir_validator_snapshots "
                    + "(fhir_version, ig_package, tx_mode, tx_server) VALUES ('R4', 'e.ig#1.0.0', 'NONE', 'http://x')"),
                    "NONE forbids a server");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".fhir_validator_snapshots "
                    + "(fhir_version, ig_package, tx_mode) VALUES ('R4', 'f.ig#1.0.0', 'REMOTE')"), "unknown mode");
            st.execute("INSERT INTO " + S + ".tx_registry_snapshot (fhir_version, fetched_at, registry_last_update, body) "
                    + "VALUES ('R4', now(), NULL, '[]')");
            assertEquals(List.of("R4"), column(c, "SELECT fhir_version FROM " + S + ".tx_registry_snapshot"));
        }
    }

    // --- API keys and usage (spec 008) ----------------------------------------------------------------------------

    /** Key expiry and the last client address are optional. */
    @Test
    void keyExpiryAndLastUsedIpAreOptional() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x09', 'ADMIN')");
            assertEquals(List.of("ops|true|true"), column(c, "SELECT name || '|' || (expires_at IS NULL) || '|' || (last_used_ip IS NULL) "
                    + "FROM " + S + ".api_keys"));
            assertApiKeysTable(c);
        }
    }

    /** The usage table, its indexes, and ON DELETE SET NULL for the key (data-model §3). */
    @Test
    void validationUsage() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertEquals(List.of("validation_usage_key_created_idx", "validation_usage_org_created_idx"), column(c,
                    "SELECT indexname FROM pg_indexes WHERE schemaname='" + S + "' AND tablename='validation_usage' "
                            + "AND indexname <> 'validation_usage_pkey' ORDER BY indexname"));
            st.execute("INSERT INTO " + S + ".api_keys (id, name, key_hash, role) VALUES ('" + ADMIN_KEY_ID + "', 'k', '\\x01', 'ADMIN')");
            st.execute("INSERT INTO " + S + ".validation_usage (started_at, request_id, api_key_id, api_key_name, org_id, "
                    + "profile_count, request_bytes, outcome, http_status, client_ip, total_ms, queue_ms, context_ms, validate_ms, "
                    + "cpu_time_ns, allocated_bytes) VALUES (now(), 'r1', '" + ADMIN_KEY_ID + "', 'k', '" + SYSTEM_ID
                    + "', 0, 10, 'valid', 200, '127.0.0.1', 5, 0, 0, 4, 1000, 2000)");
            assertEquals(List.of("0|0|0|0"), column(c, "SELECT issues_fatal || '|' || issues_error || '|' || issues_warning || '|' "
                    + "|| issues_information FROM " + S + ".validation_usage"), "issue counts default to 0");
            st.execute("DELETE FROM " + S + ".api_keys WHERE id = '" + ADMIN_KEY_ID + "'");
            assertEquals(List.of("null|k"), column(c, "SELECT coalesce(api_key_id::text, 'null') || '|' || api_key_name FROM "
                    + S + ".validation_usage"));
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".validation_usage (started_at, request_id, "
                    + "profile_count, request_bytes, outcome, http_status, total_ms, queue_ms, context_ms, validate_ms, cpu_time_ns, "
                    + "allocated_bytes) VALUES (now(), 'r2', 0, 0, 'valid', 200, 0, 0, 0, 0, 0, 0)"), "org_id is required");
        }
    }

    /** Revoking a well-known key records its hash; other keys are not recorded. */
    @Test
    void revokingAWellKnownKeyRecordsItsHash() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (id, name, key_hash, role, owner) VALUES "
                    + "('" + ADMIN_KEY_ID + "', 'system administrator', '\\x0a', 'ADMIN', '" + SYSTEM_ID + "'), "
                    + "('" + TRIAL_KEY_ID + "', 'trial validator', '\\x0b', 'VALIDATE', '" + TRIAL_ID + "'), "
                    + "('" + UUID.randomUUID() + "', 'other', '\\x0c', 'VALIDATE', '" + SYSTEM_ID + "')");
            assertEquals(List.of(), column(c, "SELECT key_hash FROM " + S + ".revoked_key_hashes"));
            st.execute("UPDATE " + S + ".api_keys SET revoked_at = now()");
            assertEquals(List.of("\\x0a", "\\x0b"), column(c, "SELECT key_hash FROM " + S + ".revoked_key_hashes ORDER BY key_hash"),
                    "the trigger records well-known keys only, not 'other'");
        }
    }

    // --- Served FHIR versions and access-control mode (spec 009) --------------------------------------------------

    /** A context is active unless it has a known inactive reason; service settings constrain the stored mode. */
    @Test
    void contextActiveFlagAndServiceSettings() throws SQLException {
        PgConnectOptions db = migrated();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package) VALUES ('R4', 'a.ig#1.0.0')");
            assertEquals(List.of("true|"), column(c, "SELECT is_active || '|' || coalesce(inactive_reason, '') FROM " + S
                    + ".fhir_validator_snapshots"));
            assertEquals(List.of("0"), column(c, "SELECT count(*)::text FROM " + S + ".service_settings"));
            assertThrows(SQLException.class, () -> st.execute("UPDATE " + S
                    + ".fhir_validator_snapshots SET is_active = false WHERE ig_package = 'a.ig#1.0.0'"), "inactive needs a reason");
            assertThrows(SQLException.class, () -> st.execute("UPDATE " + S
                    + ".fhir_validator_snapshots SET is_active = false, inactive_reason = 'other' WHERE ig_package = 'a.ig#1.0.0'"),
                    "only known reasons");
            st.execute("UPDATE " + S + ".fhir_validator_snapshots SET is_active = false, inactive_reason = 'fhir-version-disabled' "
                    + "WHERE ig_package = 'a.ig#1.0.0'");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S
                    + ".service_settings (name, value) VALUES ('acl_security', 'off')"), "acl_security is enabled|disabled");
            st.execute("INSERT INTO " + S + ".service_settings (name, value) VALUES ('acl_security', 'disabled'), ('other', 'x')");
        }
    }
}
