package nzi.fhir.validator.core.service;

import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.flywaydb.core.Flyway;
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
 * 1.0.0 merges every earlier migration into {@code V15__baseline_1_0.sql}. The earlier migrations are kept in test
 * resources under {@code db/legacy}, in the two histories a development-build database can have:
 * <ul>
 *   <li>{@link #ORIGINAL}: the original V1 to V15;</li>
 *   <li>{@link #SQUASHED_V9}: V9 as one baseline, then V10 to V15.</li>
 * </ul>
 * The tests show that the baseline builds the same schema and seed rows as both; that a database with either history
 * starts with its history unchanged and its data kept, and a build with that history still validates it; that a
 * migration after the baseline is still validated strictly; and that a database stopped before V15 is refused.
 */
class SquashedBaselineIT extends BaseTestContainer {

    private static final String S = "fhir_validator_schema";
    private static final String[] ORIGINAL = {"classpath:db/legacy/v1-v9-original", "classpath:db/legacy/v10-v15"};
    private static final String[] SQUASHED_V9 = {"classpath:db/legacy/v9-baseline", "classpath:db/legacy/v10-v15"};
    private static final String PROBE = "classpath:db/test-probe";

    private PgConnectOptions freshDatabase() throws SQLException {
        String name = "sq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    /** An earlier history, up to {@code target}. */
    private static Flyway earlier(PgConnectOptions o, String[] history, String target) {
        return DatabaseService.flywayBuilder(o).locations(history).target(MigrationVersion.fromVersion(target)).load();
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    row.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private static void execute(PgConnectOptions o, String sql) throws SQLException {
        try (Connection c = jdbc(o); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * What a later migration or the service can observe: columns in order (not their attribute numbers, which count
     * dropped columns), constraints, indexes, types, functions, triggers, the seed rows and every table's row count.
     */
    private static List<String> snapshot(PgConnectOptions o) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = jdbc(o)) {
            out.addAll(rows(c, "SELECT table_name, column_name, data_type, udt_name, character_maximum_length, "
                    + "numeric_precision, is_nullable, column_default, is_identity, identity_generation FROM information_schema.columns "
                    + "WHERE table_schema = '" + S + "' AND table_name <> 'flyway_schema_history' ORDER BY table_name, ordinal_position"));
            out.addAll(rows(c, "SELECT cl.relname, co.conname, co.contype, pg_get_constraintdef(co.oid) FROM pg_constraint co "
                    + "JOIN pg_class cl ON cl.oid = co.conrelid JOIN pg_namespace n ON n.oid = cl.relnamespace "
                    + "WHERE n.nspname = '" + S + "' AND cl.relname <> 'flyway_schema_history' ORDER BY 1, 2"));
            out.addAll(rows(c, "SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = '" + S + "' "
                    + "AND tablename <> 'flyway_schema_history' ORDER BY 1, 2"));
            out.addAll(rows(c, "SELECT t.typname, string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) FROM pg_type t "
                    + "JOIN pg_namespace n ON n.oid = t.typnamespace JOIN pg_enum e ON e.enumtypid = t.oid "
                    + "WHERE n.nspname = '" + S + "' GROUP BY 1 ORDER BY 1"));
            out.addAll(rows(c, "SELECT p.proname, pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = '" + S + "' ORDER BY 1"));
            out.addAll(rows(c, "SELECT event_object_table, trigger_name, event_manipulation, action_timing, action_statement "
                    + "FROM information_schema.triggers WHERE trigger_schema = '" + S + "' ORDER BY 1, 2, 3"));
            out.addAll(rows(c, "SELECT id, name_short, name_long, type, is_active, address_lines FROM " + S
                    + ".organisations ORDER BY id"));
            out.addAll(rows(c, "SELECT validator_id, fhir_version, included_ig_packages, is_active, revision FROM "
                    + S + ".fhir_validator_logs ORDER BY validator_id, fhir_version"));
            out.addAll(rows(c, "SELECT id, revision FROM " + S + ".fhir_package_catalog"));
            for (String table : List.of("api_keys", "revoked_key_hashes", "fhir_implementation_guides", "fhir_validator_snapshots",
                    "tx_registry_snapshot", "validation_usage", "service_settings")) {
                out.addAll(rows(c, "SELECT '" + table + "', count(*) FROM " + S + "." + table));
            }
        }
        return out;
    }

    private static List<String> history(PgConnectOptions o) throws SQLException {
        try (Connection c = jdbc(o)) {
            return rows(c, "SELECT version, description, checksum FROM " + S + ".flyway_schema_history "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank");
        }
    }

    @Test
    void baselineBuildsTheSameSchemaAsTheOriginalMigrations() throws SQLException {
        PgConnectOptions original = freshDatabase();
        PgConnectOptions baseline = freshDatabase();
        earlier(original, ORIGINAL, "15").migrate();
        DatabaseService.migrateOnce(baseline);
        assertEquals(List.of("15 | baseline 1 0"), history(baseline).stream().map(r -> r.substring(0, r.lastIndexOf(" | "))).toList(),
                "a new database runs the baseline only");
        assertEquals(snapshot(original), snapshot(baseline));
    }

    @Test
    void baselineBuildsTheSameSchemaAsTheSquashedV9Migrations() throws SQLException {
        PgConnectOptions squashed = freshDatabase();
        PgConnectOptions baseline = freshDatabase();
        earlier(squashed, SQUASHED_V9, "15").migrate();
        DatabaseService.migrateOnce(baseline);
        assertEquals(snapshot(squashed), snapshot(baseline));
    }

    @Test
    void aDatabaseWithTheOriginalHistoryStartsKeepingItsHistoryAndData() throws SQLException {
        startsKeepingHistoryAndData(ORIGINAL);
    }

    @Test
    void aDatabaseWithTheSquashedV9HistoryStartsKeepingItsHistoryAndData() throws SQLException {
        startsKeepingHistoryAndData(SQUASHED_V9);
    }

    private void startsKeepingHistoryAndData(String[] history) throws SQLException {
        PgConnectOptions db = freshDatabase();
        earlier(db, history, "15").migrate();
        execute(db, "INSERT INTO " + S + ".organisations (name_short, name_long, type) VALUES ('acme', 'Acme', 'COMMERCIAL')");
        execute(db, "INSERT INTO " + S + ".api_keys (name, key_hash, role, owner) SELECT 'ops', '\\x05', 'ADMIN', id FROM "
                + S + ".organisations WHERE name_short = 'acme'");
        execute(db, "INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, "
                + "content_raw, source) VALUES ('example.pkg', '1.0.0', '{}', '\\x00', 'UPLOAD')");
        List<String> before = history(db);

        DatabaseService.migrateOnce(db);
        DatabaseService.migrateOnce(db);

        assertEquals(before, history(db), "the history is unchanged");
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("acme"), rows(c, "SELECT o.name_short FROM " + S + ".api_keys k JOIN " + S
                    + ".organisations o ON o.id = k.owner WHERE k.name = 'ops'"));
            assertEquals(List.of("example.pkg"), rows(c, "SELECT ig_package_id FROM " + S + ".fhir_implementation_guides"));
        }
        // A build that ships this history still validates the database (switching back keeps working).
        assertDoesNotThrow(() -> DatabaseService.flywayBuilder(db).locations(history).load().validate());
    }

    @Test
    void migrationsAfterTheBaselineAreValidatedStrictly() throws SQLException {
        PgConnectOptions db = freshDatabase();
        earlier(db, ORIGINAL, "15").migrate();
        DatabaseService.migrateOnce(db, PROBE);
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("16"), rows(c, "SELECT max(version::int)::text FROM " + S + ".flyway_schema_history "
                    + "WHERE version IS NOT NULL"), "V16 is applied on top of the earlier history");
        }
        execute(db, "UPDATE " + S + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '16'");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DatabaseService.migrateOnce(db, PROBE));
        assertTrue(e.getMessage().contains("16"), e.getMessage());
    }

    @Test
    void aNewDatabaseAppliesLaterMigrationsNormally() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.migrateOnce(db, PROBE);
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("15", "16"), rows(c, "SELECT version FROM " + S + ".flyway_schema_history "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank"));
        }
    }

    @Test
    void aDatabaseStoppedBeforeTheBaselineIsRefused() throws SQLException {
        PgConnectOptions db = freshDatabase();
        earlier(db, ORIGINAL, "12").migrate();

        StartupException e = assertThrows(StartupException.class, () -> DatabaseService.migrateOnce(db));
        assertTrue(e.getMessage().contains("unsupported early build"), e.getMessage());
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("12"), rows(c, "SELECT max(version::int)::text FROM " + S + ".flyway_schema_history "
                    + "WHERE version IS NOT NULL"), "nothing was applied");
        }
    }
}
