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
                    column(c, "SELECT included_ig_packages::text FROM " + S + ".fhir_validator_logs WHERE fhir_version='R4'"));
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

        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("6")).load().migrate();

        try (Connection c = jdbc(db)) {
            assertEquals(before, column(c, counts), "IG, configuration and API key counts are unchanged");
            assertEquals(List.of("7|{hl7.fhir.dk.core#3.4.0}"), column(c, "SELECT revision || '|' || resolved_ig_packages::text FROM "
                    + S + ".fhir_validator_logs WHERE fhir_version = 'R4'"));
        }
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db)) {
            assertLatestSchema(c);
        }
    }

    /**
     * 2.0.0 (V6) to 3.0.0 (V7): every included IG stays in {@code included_ig_packages} (each becomes its own pinned
     * context), the revision is unchanged, and only the combined resolved set is dropped (spec 004 FR-035).
     */
    @Test
    void v7KeepsIncludedIgsAndRevisionAndDropsCombinedResolvedSet() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("6")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw) "
                    + "VALUES ('a.ig', '1.0.0', '{}', '\\x01'), ('b.ig', '1.0.0', '{}', '\\x02')");
            st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{a.ig#1.0.0,b.ig#1.0.0}', "
                    + "resolved_ig_packages = '{a.ig#1.0.0,b.ig#1.0.0}', revision = 5 WHERE fhir_version = 'R4'");
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x09', 'ADMIN')");
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db)) {
            assertEquals(List.of("5|{a.ig#1.0.0,b.ig#1.0.0}"), column(c, "SELECT revision || '|' || included_ig_packages::text FROM "
                    + S + ".fhir_validator_logs WHERE fhir_version = 'R4'"));
            assertEquals(List.of("2"), column(c, "SELECT count(*)::text FROM " + S + ".fhir_implementation_guides"));
            assertEquals(List.of("ops"), column(c, "SELECT name FROM " + S + ".api_keys"));
            assertLatestSchema(c);
        }
    }

    // --- V8 (3.1.0, spec 005: organisation ownership) -----------------------------------------------------------

    private static final String SYSTEM_ID = "00000000-0000-0000-0000-000000000001";

    /** (a) V8 on an empty database: exactly one SYSTEM organisation, with the well-known id. */
    @Test
    void v8CreatesTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        try (Connection c = jdbc(db)) {
            assertEquals(List.of(SYSTEM_ID + "|system|SYSTEM|true"), column(c, "SELECT id || '|' || name_short || '|' || type "
                    + "|| '|' || is_active FROM " + S + ".organisations"));
            assertLatestSchema(c);
        }
    }

    /**
     * (b) 3.0.0 (V7) to 3.1.0 (V8) with two keys, three packages and two included IGs on R4: everything is owned by
     * the system organisation and shareable, and each included IG has a system-owned, shareable snapshot row.
     */
    @Test
    void v8MakesExistingDataSystemOwnedAndShareable() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("7")).load().migrate();
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('bootstrap', '\\x01', 'ADMIN'), "
                    + "('ci', '\\x02', 'VALIDATE')");
            st.execute("INSERT INTO " + S + ".fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw) "
                    + "VALUES ('a.ig', '1.0.0', '{}', '\\x01'), ('b.ig', '1.0.0', '{}', '\\x02'), ('c.ig', '1.0.0', '{}', '\\x03')");
            st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{a.ig#1.0.0,b.ig#1.0.0}', revision = 4 "
                    + "WHERE fhir_version = 'R4'");
        }

        DatabaseService.flyway(db).migrate();

        try (Connection c = jdbc(db)) {
            assertEquals(List.of("bootstrap|" + SYSTEM_ID, "ci|" + SYSTEM_ID),
                    column(c, "SELECT name || '|' || owner FROM " + S + ".api_keys ORDER BY name"));
            assertEquals(List.of("a.ig|" + SYSTEM_ID + "|true", "b.ig|" + SYSTEM_ID + "|true", "c.ig|" + SYSTEM_ID + "|true"),
                    column(c, "SELECT ig_package_id || '|' || owner || '|' || is_shareable FROM " + S
                            + ".fhir_implementation_guides ORDER BY ig_package_id"));
            assertEquals(List.of("R4|a.ig#1.0.0|" + SYSTEM_ID + "|true", "R4|b.ig#1.0.0|" + SYSTEM_ID + "|true"),
                    column(c, "SELECT fhir_version || '|' || ig_package || '|' || owner || '|' || is_shareable FROM " + S
                            + ".fhir_validator_snapshots ORDER BY ig_package"));
            assertEquals(List.of("4|{a.ig#1.0.0,b.ig#1.0.0}"), column(c, "SELECT revision || '|' || included_ig_packages::text FROM "
                    + S + ".fhir_validator_logs WHERE fhir_version = 'R4'"), "the configuration row is unchanged (research R1)");
        }
    }

    /** (c) The system organisation is protected by constraints, even against hand-written SQL (FR-003). */
    @Test
    void v8ProtectsTheSystemOrganisation() throws SQLException {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
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

    /**
     * Spec 005 US4 (FR-004, FR-006, SC-001): on a 3.0.0 database, starting 3.1 applies only the configured system
     * organisation fields, keeps the bootstrap key system-owned, and previous clients keep working unchanged.
     */
    @Test
    void startWithSystemOrganisationSettingsOnAnUpgradedDatabase() throws Exception {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flywayBuilder(db).target(MigrationVersion.fromVersion("7")).load().migrate();
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        try {
            io.vertx.core.json.JsonObject config = nzi.fhir.validator.testsupport.AppHarness.baseConfig(db.getHost(), db.getPort(),
                    db.getDatabase(), db.getUser(), db.getPassword());
            config.put("systemOrg", new io.vertx.core.json.JsonObject().put("nameLong", "Acme Ops").put("email", "ops@acme.example"));
            nzi.fhir.validator.testsupport.AppHarness app = join(nzi.fhir.validator.testsupport.AppHarness.start(vertx, config));
            try {
                join(app.awaitReady(90_000));
                // A 3.0 client: validate without and with ig, include, register; same status and fields as before.
                var client = app.client();
                var services = app.bootstrap().services();
                join(nzi.fhir.validator.testsupport.TestPackages.storePackage(services,
                        nzi.fhir.validator.testsupport.TestPackages.profilePackage("upgrade.ig", "1.0.0")));
                var base = join(client.post(app.port(), "localhost", "/R4/validate").putHeader("Content-Type", "application/fhir+json")
                        .sendBuffer(io.vertx.core.buffer.Buffer.buffer(nzi.fhir.validator.testsupport.TestPackages.minimalPatient())));
                assertEquals(200, base.statusCode(), base.bodyAsString());
                for (String field : List.of("valid", "messages", "fhirVersion", "appliedProfiles", "igPackage")) {
                    assertTrue(base.bodyAsJsonObject().containsKey(field), field);
                }
                var withIg = join(client.post(app.port(), "localhost", "/R4/validate").addQueryParam("ig", "upgrade.ig#1.0.0")
                        .putHeader("Content-Type", "application/fhir+json")
                        .sendBuffer(io.vertx.core.buffer.Buffer.buffer(nzi.fhir.validator.testsupport.TestPackages.minimalPatient())));
                assertEquals(200, withIg.statusCode(), withIg.bodyAsString());
                assertEquals("upgrade.ig#1.0.0", withIg.getHeader("X-Validation-Context"));
                var include = join(nzi.fhir.validator.testsupport.AppHarness.asAdmin(client.post(app.port(), "localhost", "/R4/include-ig"))
                        .sendJsonObject(new io.vertx.core.json.JsonObject().put("igPackageId", "upgrade.ig").put("igPackageVersion", "1.0.0")));
                assertEquals(200, include.statusCode(), include.bodyAsString());
                for (String field : List.of("status", "fhirVersion", "key", "revision", "included", "resolved", "warnings")) {
                    assertTrue(include.bodyAsJsonObject().containsKey(field), field);
                }
                var register = join(nzi.fhir.validator.testsupport.AppHarness.asAdmin(client.post(app.port(), "localhost", "/igs/register"))
                        .sendJsonObject(new io.vertx.core.json.JsonObject().put("name", "upgrade.ig").put("version", "1.0.0")));
                assertEquals(200, register.statusCode(), register.bodyAsString());
                for (String field : List.of("status", "name", "version", "downloaded", "warnings")) {
                    assertTrue(register.bodyAsJsonObject().containsKey(field), field);
                }
                java.nio.file.Path tgz = java.nio.file.Files.createTempFile("upgrade-upload", ".tgz");
                java.nio.file.Files.write(tgz, nzi.fhir.validator.testsupport.TestPackages.profilePackage("upgrade.upload", "1.0.0"));
                var upload = join(nzi.fhir.validator.testsupport.AppHarness.asAdmin(client.post(app.port(), "localhost", "/igs/upload"))
                        .sendMultipartForm(io.vertx.ext.web.multipart.MultipartForm.create()
                                .binaryFileUpload("file", "upgrade-upload.tgz", tgz.toString(), "application/gzip")));
                assertEquals(200, upload.statusCode(), upload.bodyAsString());
                for (String field : List.of("status", "name", "version", "downloaded", "warnings")) {
                    assertTrue(upload.bodyAsJsonObject().containsKey(field), field);
                }
                assertEquals("upgrade.upload", upload.bodyAsJsonObject().getString("name"));
            } finally {
                join(app.stop());
            }
            try (Connection c = jdbc(db)) {
                assertEquals(List.of("system|Acme Ops|ops@acme.example|null"), column(c, "SELECT name_short || '|' || name_long || '|' "
                        + "|| email || '|' || coalesce(phone, 'null') FROM " + S + ".organisations WHERE type = 'SYSTEM'"));
                assertEquals(List.of(SYSTEM_ID), column(c, "SELECT owner::text FROM " + S + ".api_keys WHERE name = 'bootstrap'"));
            }

            // A second start with only the email changed updates only the email.
            config.put("systemOrg", new io.vertx.core.json.JsonObject().put("email", "ops2@acme.example"));
            nzi.fhir.validator.testsupport.AppHarness again = join(nzi.fhir.validator.testsupport.AppHarness.start(vertx, config));
            try {
                join(again.bootstrap().warmUp());
            } finally {
                join(again.stop());
            }
            try (Connection c = jdbc(db)) {
                assertEquals(List.of("Acme Ops|ops2@acme.example"), column(c, "SELECT name_long || '|' || email FROM " + S
                        + ".organisations WHERE type = 'SYSTEM'"));
            }
        } finally {
            join(vertx.close());
        }
    }

    private static <T> T join(io.vertx.core.Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
    }
}
