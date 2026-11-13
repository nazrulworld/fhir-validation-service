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
 * 4.3.0 squashed the original V1 to V9 into {@code V9__baseline_4_0.sql}. The originals are kept under
 * {@code db/legacy-migration} (test resources) to show that:
 * <ul>
 *   <li>the baseline builds the same schema and seed rows as the original chain, before and after V10 onwards;</li>
 *   <li>a database that ran the original chain (any 4.x) upgrades with its history unchanged and its data kept, and
 *       4.2 (the original V1 to V9) still validates against it, so rolling restarts and rollback keep working;</li>
 *   <li>V10 onwards are still validated strictly on such a database;</li>
 *   <li>a schema older than 4.0.0 is refused without retrying.</li>
 * </ul>
 */
class SquashedBaselineIT extends BaseTestContainer {

    private static final String S = "fhir_validator_schema";
    private static final String LEGACY = "classpath:db/legacy-migration";

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

    /** The original chain (what 4.0 to 4.2 ship), up to {@code target}. */
    private static Flyway legacy(PgConnectOptions o, String target) {
        return DatabaseService.flywayBuilder(o).locations(LEGACY).target(MigrationVersion.fromVersion(target)).load();
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
     * dropped columns), constraints, indexes, types, functions, triggers, and the seed rows.
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
            out.addAll(rows(c, "SELECT event_object_table, trigger_name, event_manipulation, action_statement "
                    + "FROM information_schema.triggers WHERE trigger_schema = '" + S + "' ORDER BY 1, 2, 3"));
            out.addAll(rows(c, "SELECT id, name_short, name_long, type, is_active FROM " + S + ".organisations ORDER BY id"));
            out.addAll(rows(c, "SELECT validator_id, fhir_version, included_ig_packages, is_active, revision FROM "
                    + S + ".fhir_validator_logs ORDER BY validator_id, fhir_version"));
            out.addAll(rows(c, "SELECT id, revision FROM " + S + ".fhir_package_catalog"));
            out.addAll(rows(c, "SELECT name, role, owner, revoked_at IS NULL FROM " + S + ".api_keys ORDER BY name"));
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
    void baselineBuildsTheSameSchemaAsTheOriginalChain() throws SQLException {
        PgConnectOptions original = freshDatabase();
        PgConnectOptions squashed = freshDatabase();
        legacy(original, "9").migrate();
        DatabaseService.flywayBuilder(squashed).target(MigrationVersion.fromVersion("9")).load().migrate();
        assertEquals(snapshot(original), snapshot(squashed), "the 4.0.0 schema");

        DatabaseService.migrateOnce(original);
        DatabaseService.migrateOnce(squashed);
        assertEquals(snapshot(original), snapshot(squashed), "the latest schema");
    }

    @Test
    void a4xDatabaseUpgradesKeepingItsHistoryAndData() throws SQLException {
        PgConnectOptions db = freshDatabase();
        legacy(db, "9").migrate();
        execute(db, "INSERT INTO " + S + ".organisations (name_short, name_long, type) VALUES ('acme', 'Acme', 'COMMERCIAL')");
        execute(db, "INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x05', 'ADMIN')");
        execute(db, "INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, "
                + "content_raw, source) VALUES ('example.pkg', '1.0.0', '{}', '\\x00', 'UPLOAD')");
        List<String> retired = history(db);

        DatabaseService.migrateOnce(db);

        List<String> after = history(db);
        assertEquals(retired, after.subList(0, retired.size()), "the original V1 to V9 rows are unchanged");
        assertEquals(List.of("10", "11", "12", "13", "14", "15"),
                after.subList(retired.size(), after.size()).stream().map(r -> r.split(" \\| ")[0]).toList());
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("acme"), rows(c, "SELECT name_short FROM " + S + ".organisations WHERE type = 'COMMERCIAL'"));
            assertEquals(List.of("ops"), rows(c, "SELECT name FROM " + S + ".api_keys WHERE name = 'ops'"));
            assertEquals(List.of("example.pkg"), rows(c, "SELECT ig_package_id FROM " + S + ".fhir_implementation_guides"));
        }

        DatabaseService.migrateOnce(db);
        assertEquals(after, history(db), "a second start changes nothing");

        // 4.2 ships the original V1 to V9 and ignores later (future) versions: it still validates this database.
        assertDoesNotThrow(() -> DatabaseService.flywayBuilder(db).locations(LEGACY).load().validate());
    }

    @Test
    void laterMigrationsAreStillValidatedStrictly() throws SQLException {
        PgConnectOptions db = freshDatabase();
        legacy(db, "9").migrate();
        DatabaseService.migrateOnce(db);
        execute(db, "UPDATE " + S + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '10'");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DatabaseService.migrateOnce(db));
        assertTrue(e.getMessage().contains("10"), e.getMessage());
    }

    @Test
    void aSchemaOlderThan4IsRefused() throws SQLException {
        PgConnectOptions db = freshDatabase();
        legacy(db, "8").migrate();

        StartupException e = assertThrows(StartupException.class, () -> DatabaseService.migrateOnce(db));
        assertTrue(e.getMessage().contains("4.2.0"), e.getMessage());
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("8"), rows(c, "SELECT max(version::int)::text FROM " + S + ".flyway_schema_history "
                    + "WHERE version IS NOT NULL"), "nothing was applied");
        }
    }
}
