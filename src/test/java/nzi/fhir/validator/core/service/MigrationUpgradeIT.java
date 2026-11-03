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
 * Flyway must apply cleanly to an empty database and on top of earlier releases' schemas: 0.1.x (V2) and 1.2.0 (V5).
 * V6 (2.0.0) drops the custom profile storage and nothing else (spec 003, FR-013, FR-015).
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
    }

    private static void assertApiKeysTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('k', '\\x01', 'ADMIN')");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('k2', '\\x02', 'ROOT')"));
        }
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

    @Test
    void upgradesPreviousReleaseSchemaKeepingData() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("2")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw) "
                    + "VALUES ('hl7.fhir.dk.core', '3.4.0', '{}', '\\x01')");
            st.execute("INSERT INTO " + S + ".fhir_validator_logs (validator_id, fhir_version, included_ig_packages) "
                    + "VALUES ('standard_validator', 'R4', '{hl7.fhir.dk.core#3.4.0}')");
            // Shape written by earlier releases: the document stored as a JSON string value.
            st.execute("INSERT INTO " + S + ".fhir_profiles (url, profile_json, fhir_version) "
                    + "VALUES ('http://ex.org/p', to_jsonb('{\"resourceType\":\"StructureDefinition\"}'::text), '4.0.1')");
            st.execute("UPDATE " + S + ".fhir_implementation_guides SET ig_package_meta = to_jsonb('{\"name\":\"hl7.fhir.dk.core\"}'::text)");
        }

        // The V3 repairs of fhir_profiles are checked at 1.2.0's schema (V5), before V6 drops the table.
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("5")).load().migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of("t"), column(c, "SELECT (modified_at IS NOT NULL)::text FROM " + S + ".fhir_profiles").stream()
                    .map(v -> v.substring(0, 1)).toList());
            assertEquals(List.of("object"), column(c, "SELECT DISTINCT jsonb_typeof(profile_json) FROM " + S + ".fhir_profiles"));
            assertEquals(List.of("StructureDefinition"), column(c, "SELECT profile_json->>'resourceType' FROM " + S + ".fhir_profiles"));
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db)) {
            assertEquals(List.of("3.4.0"), column(c, "SELECT ig_package_version FROM " + S + ".fhir_implementation_guides WHERE ig_package_id='hl7.fhir.dk.core'"));
            assertEquals(List.of("{hl7.fhir.dk.core#3.4.0}"),
                    column(c, "SELECT resolved_ig_packages::text FROM " + S + ".fhir_validator_logs WHERE fhir_version='R4'"));
            assertEquals(List.of("hl7.fhir.dk.core"), column(c, "SELECT ig_package_meta->>'name' FROM " + S
                    + ".fhir_implementation_guides WHERE ig_package_id='hl7.fhir.dk.core'"));
            assertLatestSchema(c);
            assertApiKeysTable(c);
        }
    }

    /**
     * 1.2.0 (V5) to 2.0.0 (V6) with stored custom profiles: the profiles and the never-used included_profiles column
     * are dropped unconditionally (an accepted risk, FR-015), and IG packages, configuration rows and API keys survive
     * unchanged.
     */
    @Test
    void v6DropsCustomProfilesAndKeepsEverythingElse() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("5")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw) "
                    + "VALUES ('hl7.fhir.dk.core', '3.4.0', '{}', '\\x01')");
            st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{hl7.fhir.dk.core#3.4.0}', "
                    + "resolved_ig_packages = '{hl7.fhir.dk.core#3.4.0}', revision = 7 WHERE fhir_version = 'R4'");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x09', 'ADMIN')");
            st.execute("INSERT INTO " + S + ".fhir_profiles (url, profile_json, fhir_version, modified_at) VALUES "
                    + "('http://ex.org/p1', '{\"resourceType\":\"StructureDefinition\"}', '4.0.1', now()), "
                    + "('http://ex.org/p2', '{\"resourceType\":\"StructureDefinition\"}', '5.0.0', now())");
        }
        String counts = "SELECT (SELECT count(*) FROM " + S + ".fhir_implementation_guides) || '/' || "
                + "(SELECT count(*) FROM " + S + ".fhir_validator_logs) || '/' || (SELECT count(*) FROM " + S + ".api_keys)";
        List<String> before;
        try (Connection c = jdbc(db)) {
            before = column(c, counts);
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db)) {
            assertEquals(before, column(c, counts), "IG, configuration and API key counts are unchanged");
            assertEquals(List.of("7|{hl7.fhir.dk.core#3.4.0}"), column(c, "SELECT revision || '|' || resolved_ig_packages::text FROM "
                    + S + ".fhir_validator_logs WHERE fhir_version = 'R4'"));
            assertLatestSchema(c);
        }
    }
}
