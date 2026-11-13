package nzi.fhir.validator.core.service;

import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.flywaydb.core.api.MigrationVersion;
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
 * Flyway must apply cleanly to an empty database and from each 4.x schema onwards. V9 is the 4.0.0 baseline (4.3.0
 * squashed the original V1 to V9; upgrades of a database that ran them are covered by SquashedBaselineIT). V11 (4.1.0)
 * re-keys the system organisation (spec 008).
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

    // --- V8 (3.1.0, spec 005: organisation ownership) -----------------------------------------------------------

    private static final String SYSTEM_ID = "00000000-0000-0000-0000-000000000001";

    /** (a) The 4.0 baseline on an empty database: exactly one SYSTEM organisation, with the (4.0) well-known id. */
    @Test
    void v8CreatesTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("9")).load().migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of(SYSTEM_ID + "|system|SYSTEM|true"), column(c, "SELECT id || '|' || name_short || '|' || type "
                    + "|| '|' || is_active FROM " + S + ".organisations"));
            assertLatestSchema(c);
        }
    }

    /** (c) The system organisation is protected by constraints, even against hand-written SQL (FR-003). */
    @Test
    void v8ProtectsTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("9")).load().migrate();
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

    /** (d) Short names are unique regardless of case. */
    @Test
    void v8ShortNamesAreUniqueIgnoringCase() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
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

    // --- V9 (4.0.0, spec 007: terminology per context) -----------------------------------------------------------

    /** V9 on an empty database: the terminology columns and their constraint, and the registry snapshot table. */
    @Test
    void v9AddsTerminologyColumnsAndRegistrySnapshot() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
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
            assertLatestSchema(c);
        }
    }


    // --- V10–V11 (4.1.0, spec 008: key expiry and audit, well-known organisations) ---------------------------------

    private static final String NEW_SYSTEM_ID = "00000000-0000-0000-0000-100000000001";
    private static final String TRIAL_ID = "00000000-0000-0000-0000-100000000002";
    private static final String ADMIN_KEY_ID = "00000000-0000-0000-0000-200000000001";

    /** 4.0.0 (V9) to V10: both columns exist, are nullable, and are NULL on existing keys. */
    @Test
    void v10AddsExpiryAndLastUsedIp() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("9")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x09', 'ADMIN')");
        }
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("10")).load().migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("ops|t|t"), column(c, "SELECT name || '|' || (expires_at IS NULL) || '|' || (last_used_ip IS NULL) "
                    + "FROM " + S + ".api_keys").stream().map(v -> v.replace("true", "t")).toList());
            assertApiKeysTable(c);
        }
    }

    /**
     * Spec 008 US3 (FR-024, SC-008): a 4.0 database with system-owned keys, IG packages and contexts is re-keyed to the
     * well-known system id; another organisation's rows are untouched; the trial organisation is seeded and the
     * bootstrap key gets its well-known id and name.
     */
    @Test
    void v11ReKeysTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("10")).load().migrate();
        String other = UUID.randomUUID().toString();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".organisations (id, name_short, name_long, type) VALUES ('" + other
                    + "', 'acme', 'Acme', 'COMMERCIAL')");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('bootstrap', '\\x01', 'ADMIN'), "
                    + "('ci', '\\x02', 'VALIDATE')");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role, owner) VALUES ('acme-ci', '\\x03', 'VALIDATE', '" + other + "')");
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw, owner) "
                    + "VALUES ('a.ig', '1.0.0', '{}', '\\x01', '" + SYSTEM_ID + "'), ('b.ig', '1.0.0', '{}', '\\x02', '" + other + "')");
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package) VALUES ('R4', 'a.ig#1.0.0')");
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package, owner) VALUES ('R4', 'b.ig#1.0.0', '" + other + "')");
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertEquals(List.of("acme-ci|" + other, "ci|" + NEW_SYSTEM_ID, "system administrator|" + NEW_SYSTEM_ID),
                    column(c, "SELECT name || '|' || owner FROM " + S + ".api_keys ORDER BY name"));
            assertEquals(List.of("a.ig|" + NEW_SYSTEM_ID, "b.ig|" + other),
                    column(c, "SELECT ig_package_id || '|' || owner FROM " + S + ".fhir_implementation_guides ORDER BY ig_package_id"));
            assertEquals(List.of("a.ig#1.0.0|" + NEW_SYSTEM_ID, "b.ig#1.0.0|" + other),
                    column(c, "SELECT ig_package || '|' || owner FROM " + S + ".fhir_validator_snapshots ORDER BY ig_package"));
            assertEquals(List.of("0"), column(c, "SELECT count(*)::text FROM " + S + ".organisations WHERE id = '" + SYSTEM_ID + "'"));
            for (String table : List.of("api_keys", "fhir_implementation_guides", "fhir_validator_snapshots")) {
                List<String> dflt = column(c, "SELECT column_default FROM information_schema.columns WHERE table_schema='" + S
                        + "' AND table_name='" + table + "' AND column_name='owner'");
                assertTrue(dflt.get(0).contains("100000000001"), table + " default: " + dflt);
                assertEquals(List.of("r"), column(c, "SELECT confdeltype::text FROM pg_constraint WHERE conname = '" + table + "_owner_fkey'"),
                        table + "_owner_fkey is ON DELETE RESTRICT");
            }
            assertEquals(List.of(ADMIN_KEY_ID + "|\\x01"), column(c, "SELECT id || '|' || key_hash FROM " + S
                    + ".api_keys WHERE name = 'system administrator'"), "the bootstrap key keeps its hash");
            assertEquals(List.of(NEW_SYSTEM_ID + "|system|SYSTEM|true", TRIAL_ID + "|trial|NON_COMMERCIAL|true"),
                    column(c, "SELECT id || '|' || name_short || '|' || type || '|' || is_active FROM " + S
                            + ".organisations WHERE id IN ('" + NEW_SYSTEM_ID + "', '" + TRIAL_ID + "') ORDER BY id"));
            assertEquals(List.of("Free trial validation service"), column(c, "SELECT name_long FROM " + S
                    + ".organisations WHERE id = '" + TRIAL_ID + "'"));
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".organisations (name_short, name_long, type) "
                    + "VALUES ('other-system', 'Other', 'SYSTEM')"), "a second SYSTEM organisation");
            assertThrows(SQLException.class, () -> st.execute("UPDATE " + S + ".organisations SET type = 'COMMERCIAL' "
                    + "WHERE id = '" + NEW_SYSTEM_ID + "'"), "demoting the system organisation");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('new', '\\x07', 'ADMIN')");
            assertEquals(List.of(NEW_SYSTEM_ID), column(c, "SELECT owner::text FROM " + S + ".api_keys WHERE name = 'new'"),
                    "new keys default to the new system id");
            assertLatestSchema(c);
        }
    }

    /** An existing organisation named 'trial' is left alone by V11; the startup check reports the clash (US2). */
    @Test
    void v11LeavesAnExistingTrialNameAlone() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("10")).load().migrate();
        String other = UUID.randomUUID().toString();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".organisations (id, name_short, name_long, type) VALUES ('" + other
                    + "', 'trial', 'Someone else', 'COMMERCIAL')");
        }
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of(other + "|Someone else|COMMERCIAL"), column(c, "SELECT id || '|' || name_long || '|' || type FROM "
                    + S + ".organisations WHERE name_short = 'trial'"));
            assertEquals(List.of("0"), column(c, "SELECT count(*)::text FROM " + S + ".organisations WHERE id = '" + TRIAL_ID + "'"));
        }
    }

    /** V11 doesn't rename the bootstrap key when the well-known name is already taken. */
    @Test
    void v11SkipsTheBootstrapRenameWhenTheNameIsTaken() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("10")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('bootstrap', '\\x01', 'ADMIN'), "
                    + "('system administrator', '\\x02', 'ADMIN')");
        }
        List<String> before;
        try (Connection c = jdbc(db)) {
            before = column(c, "SELECT id || '|' || name FROM " + S + ".api_keys WHERE name = 'bootstrap'");
        }
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(before, column(c, "SELECT id || '|' || name FROM " + S + ".api_keys WHERE name = 'bootstrap'"));
            assertEquals(List.of("0"), column(c, "SELECT count(*)::text FROM " + S + ".api_keys WHERE id = '" + ADMIN_KEY_ID + "'"));
        }
    }

    /** V12 (spec 008, data-model §3): the usage table, its indexes, and ON DELETE SET NULL for the key. */
    @Test
    void v12CreatesValidationUsage() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertEquals(List.of("validation_usage_key_created_idx", "validation_usage_org_created_idx"), column(c,
                    "SELECT indexname FROM pg_indexes WHERE schemaname='" + S + "' AND tablename='validation_usage' "
                            + "AND indexname <> 'validation_usage_pkey' ORDER BY indexname"));
            st.execute("INSERT INTO " + S + ".api_keys (id, name, key_hash, role) VALUES ('" + ADMIN_KEY_ID + "', 'k', '\\x01', 'ADMIN')");
            st.execute("INSERT INTO " + S + ".validation_usage (started_at, request_id, api_key_id, api_key_name, org_id, "
                    + "profile_count, request_bytes, outcome, http_status, client_ip, total_ms, queue_ms, context_ms, validate_ms, "
                    + "cpu_time_ns, allocated_bytes) VALUES (now(), 'r1', '" + ADMIN_KEY_ID + "', 'k', '" + NEW_SYSTEM_ID
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

    /** V13 (code review): seeded keys revoked before it ran are recorded, and later revocations by the trigger. */
    @Test
    void v13RecordsRevokedSeededKeys() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("12")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (id, name, key_hash, role, owner, revoked_at) VALUES ('"
                    + ADMIN_KEY_ID + "', 'system administrator', '\\x0a', 'ADMIN', '" + NEW_SYSTEM_ID + "', now())");
            st.execute("INSERT INTO " + S + ".api_keys (id, name, key_hash, role, owner) VALUES "
                    + "('00000000-0000-0000-0000-200000000002', 'trial validator', '\\x0b', 'VALIDATE', '" + TRIAL_ID + "'), "
                    + "('" + UUID.randomUUID() + "', 'other', '\\x0c', 'VALIDATE', '" + NEW_SYSTEM_ID + "')");
        }
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertEquals(List.of("\\x0a"), column(c, "SELECT key_hash FROM " + S + ".revoked_key_hashes"), "already revoked");
            st.execute("UPDATE " + S + ".api_keys SET revoked_at = now()");
            assertEquals(List.of("\\x0a", "\\x0b"), column(c, "SELECT key_hash FROM " + S + ".revoked_key_hashes ORDER BY key_hash"),
                    "the trigger records seeded keys only, not 'other'");
        }
    }

    /**
     * Spec 009 (FR-007, US3-4, T005): a populated 4.1.0 (V13) schema migrates to V15 with every validator context
     * active, a row for each included package that had none, and an empty service_settings table.
     */
    @Test
    void v14v15MarkEveryContextActiveAndAddServiceSettings() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("13")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{a.ig#1.0.0,b.ig#1.0.0}', revision = 3 "
                    + "WHERE fhir_version = 'R4'");
            st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{c.ig#2.0.0}', revision = 2 "
                    + "WHERE fhir_version = 'R5'");
            // b.ig has no snapshot row (the backfill must add it); a.ig and c.ig have one.
            st.execute("INSERT INTO " + S + ".fhir_validator_snapshots (fhir_version, ig_package, is_shareable) VALUES "
                    + "('R4', 'a.ig#1.0.0', false), ('R5', 'c.ig#2.0.0', true)");
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            assertEquals(List.of("R4|a.ig#1.0.0|false|true|", "R4|b.ig#1.0.0|true|true|", "R5|c.ig#2.0.0|true|true|"),
                    column(c, "SELECT fhir_version || '|' || ig_package || '|' || is_shareable || '|' || is_active || '|' "
                            + "|| coalesce(inactive_reason, '') FROM " + S + ".fhir_validator_snapshots ORDER BY 1"));
            assertEquals(List.of(NEW_SYSTEM_ID), column(c, "SELECT DISTINCT owner::text FROM " + S
                    + ".fhir_validator_snapshots WHERE ig_package = 'b.ig#1.0.0'"), "the backfilled row is system-owned");
            assertEquals(List.of("3|{a.ig#1.0.0,b.ig#1.0.0}"), column(c, "SELECT revision || '|' || included_ig_packages::text FROM "
                    + S + ".fhir_validator_logs WHERE fhir_version = 'R4'"), "the configuration row is unchanged");
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

    private static <T> T join(io.vertx.core.Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
    }
}
