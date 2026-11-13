package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The administrative command line in-process against PostgreSQL (spec 005 US3, FR-022 to FR-026, SC-005, SC-006):
 * file plus flag input, exit codes, keys shown once and stored hashed, system-organisation protections, and JSON only
 * on stdout.
 */
class AdminCliIT extends BaseTestContainer {

    /** One command's outcome. */
    record Run(int exit, String out, String err) {
        JsonObject json() {
            return new JsonObject(out);
        }

        JsonArray array() {
            return new JsonArray(out);
        }
    }

    private Vertx vertx;
    private AppHarness app;

    @BeforeEach
    void setUp() {
        startContainers();
        resetSchema();
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            await(app.stop());
        }
        await(vertx.close());
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("FV_PROFILE", "dev");
        env.put("FV_PG_HOST", getPostgresHost());
        env.put("FV_PG_PORT", String.valueOf(getPostgresPort()));
        env.put("FV_PG_DATABASE", POSTGRES_DATABASE);
        env.put("FV_PG_USER", POSTGRES_USERNAME);
        env.put("FV_PG_PASSWORD", POSTGRES_PASSWORD);
        env.put("FV_PG_SSL_MODE", "disable");
        return env;
    }

    private static Run cli(String... args) {
        return cliWithStdin("", args);
    }

    private static Run cliWithStdin(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = AdminCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                env(), new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertExit(Run r, int exit) {
        assertEquals(exit, r.exit(), "stdout: " + r.out() + " stderr: " + r.err());
        if (exit == 0) {
            assertDoesNotThrow(() -> r.out().trim().startsWith("[") ? r.array() : r.json(), "stdout is one JSON document");
        } else {
            assertEquals("", r.out(), "nothing on stdout on failure");
            assertEquals(1, r.err().trim().lines().count(), "one error line: " + r.err());
            assertTrue(r.err().startsWith("error: "), r.err());
        }
    }

    /** Polls {@code condition} on Vert.x timers (no sleeping threads) until it holds; fails after {@code timeoutMs}. */
    private void eventually(long timeoutMs, String what, BooleanSupplier condition) throws Exception {
        await(nzi.fhir.validator.testsupport.Await.until(vertx, timeoutMs, what, () -> vertx.executeBlocking(condition::getAsBoolean)));
    }

    private Run createAcme() {
        return cli("org", "create", "--name-short", "acme", "--name-long", "Acme Health ApS", "--type", "COMMERCIAL",
                "--country", "DK");
    }

    @Test
    void createFromAFileWithAFlagOverride(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("acme.json");
        Files.writeString(file, new JsonObject().put("nameShort", "acme").put("nameLong", "Acme Health ApS")
                .put("type", "COMMERCIAL").put("email", "file@acme.example").put("addressLines", new JsonArray().add("Street 1"))
                .encode());
        Run r = cli("org", "create", "--file", file.toString(), "--email", "x@y.dk");
        assertExit(r, 0);
        JsonObject o = r.json();
        assertEquals("acme", o.getString("nameShort"));
        assertEquals("Acme Health ApS", o.getString("nameLong"));
        assertEquals("x@y.dk", o.getString("email"), "the flag overrides the file");
        assertEquals(new JsonArray().add("Street 1"), o.getJsonArray("addressLines"));
        assertTrue(o.getBoolean("active"));
        assertNotNull(o.getString("id"));
        assertNull(o.getString("updatedAt"));
    }

    @Test
    void validationAndConflictsHaveTheirExitCodes() {
        Run missing = cli("org", "create", "--name-short", "acme", "--type", "COMMERCIAL");
        assertExit(missing, 2);
        assertTrue(missing.err().contains("nameLong"), missing.err());
        assertExit(createAcme(), 0);
        assertExit(cli("org", "create", "--name-short", "ACME", "--name-long", "Again", "--type", "COMMERCIAL"), 4);
        assertExit(cli("org", "create", "--name-short", "sys2", "--name-long", "Another", "--type", "SYSTEM"), 4);
        assertExit(cli("org", "create", "--colour", "red"), 2);
        assertExit(cli("org", "show", "--name-short", "nobody"), 3);
        assertExit(cli("org", "deactivate", "--name-short", "system"), 4);
    }

    @Test
    void updateChangesOnlyTheGivenFieldsAndRenameBumpsTheCatalog() throws Exception {
        assertExit(createAcme(), 0);
        Run updated = cli("org", "update", "--name-short", "acme", "--phone", "+45 12 34 56 78");
        assertExit(updated, 0);
        assertEquals("+45 12 34 56 78", updated.json().getString("phone"));
        assertEquals("DK", updated.json().getString("country"), "untouched");
        assertNotNull(updated.json().getString("updatedAt"));

        var pool = createPgPool(vertx);
        try {
            long before = await(ConfigRevisionRepository.catalogRevision(pool));
            Run renamed = cli("org", "update", "--name-short", "acme", "--rename", "acme2");
            assertExit(renamed, 0);
            assertEquals("acme2", renamed.json().getString("nameShort"));
            assertEquals(before + 1, await(ConfigRevisionRepository.catalogRevision(pool)));
        } finally {
            await(pool.close());
        }
        Run list = cli("org", "list");
        assertExit(list, 0);
        // Spec 008: the trial organisation is seeded by the migration.
        assertEquals(List.of("acme2", "system", "trial"), list.array().stream().map(o -> ((JsonObject) o).getString("nameShort")).toList());
        assertExit(cli("org", "list", "--active", "false"), 0);
        assertEquals(0, cli("org", "list", "--active", "false").array().size());
    }

    @Test
    void keysAreShownOnceWorkAndFollowTheOrganisation() throws Exception {
        assertExit(createAcme(), 0);
        Run created = cli("key", "create", "--org", "acme", "--name", "acme-ci", "--role", "VALIDATE");
        assertExit(created, 0);
        String key = created.json().getString("key");
        assertNotNull(key);
        assertEquals(43, key.length());
        assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
        assertEquals("acme", created.json().getString("org"));

        // Spec 008: the keys installed from configuration have reserved names; 'bootstrap' is an ordinary name now.
        assertExit(cli("key", "create", "--org", "acme", "--name", "system administrator", "--role", "ADMIN"), 4);
        assertExit(cli("key", "create", "--org", "acme", "--name", "Trial Validator", "--role", "VALIDATE"), 4);
        // Seeded keys may be revoked by name (none is installed here: no server has started, so 3 = not found).
        assertExit(cli("key", "revoke", "--name", "trial validator"), 3);
        assertExit(cli("key", "create", "--org", "acme", "--name", "bootstrap", "--role", "VALIDATE"), 0);
        assertExit(cli("key", "create", "--org", "acme", "--name", "acme-ci", "--role", "ADMIN"), 4);
        assertExit(cli("key", "create", "--org", "nobody", "--name", "x-ci", "--role", "ADMIN"), 3);
        Run fromStdin = cliWithStdin("own-key-0123456789abcdefghijklmnopqrstu\n",
                "key", "create", "--org", "acme", "--name", "acme-own", "--role", "VALIDATE", "--key-stdin");
        assertExit(fromStdin, 0);
        assertFalse(fromStdin.json().containsKey("key"), "a key from stdin is not echoed");

        // Spec 008 FR-006: --expires takes a future ISO-8601 instant.
        Run expiring = cli("key", "create", "--org", "acme", "--name", "acme-temp", "--role", "VALIDATE",
                "--expires", "2099-01-01T00:00:00Z");
        assertExit(expiring, 0);
        assertEquals("2099-01-01T00:00:00Z", expiring.json().getString("expiresAt"));
        assertExit(cli("key", "create", "--org", "acme", "--name", "acme-past", "--role", "VALIDATE",
                "--expires", "2000-01-01T00:00:00Z"), 2);
        assertExit(cli("key", "create", "--org", "acme", "--name", "acme-bad", "--role", "VALIDATE", "--expires", "tomorrow"), 2);
        Run temp = cli("key", "list", "--org", "acme");
        assertExit(temp, 0);
        JsonObject tempRow = temp.array().stream().map(o -> (JsonObject) o).filter(o -> o.getString("name").equals("acme-temp"))
                .findFirst().orElseThrow();
        assertEquals("2099-01-01T00:00:00Z", tempRow.getString("expiresAt"));
        assertTrue(tempRow.containsKey("lastUsedIp") && tempRow.getValue("lastUsedIp") == null, tempRow.encode());
        assertExit(cli("key", "revoke", "--name", "acme-temp"), 0);

        Run list = cli("key", "list", "--org", "acme");
        assertExit(list, 0);
        assertFalse(list.out().contains(key), "the key is never listed");
        assertFalse(list.out().contains("own-key-0123"), "the key is never listed");
        assertFalse(list.out().toLowerCase().contains("hash"));
        assertEquals(4, list.array().size());

        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("auth").put("cacheTtlMs", 200);
        app = await(AppHarness.start(vertx, config));
        await(app.awaitReady(90_000));
        assertEquals(200, validate(key).statusCode(), "the printed key authenticates");

        assertExit(cli("org", "deactivate", "--name-short", "acme"), 0);
        eventually(10_000, "the deactivated organisation's key is refused", () -> status(key) == 403);
        assertExit(cli("org", "activate", "--name-short", "acme"), 0);
        eventually(10_000, "the reactivated organisation's key works", () -> status(key) == 200);

        Run revoked = cli("key", "revoke", "--name", "acme-ci");
        assertExit(revoked, 0);
        assertTrue(revoked.json().getBoolean("revoked"));
        eventually(10_000, "the revoked key is refused", () -> status(key) == 401);
        assertExit(cli("key", "revoke", "--name", "no-such-key"), 3);
    }

    /** Spec 008 FR-022: usage summary per organisation or key, since a time. */
    @Test
    void usageSummaryByOrgAndKey() throws Exception {
        assertExit(createAcme(), 0);
        assertExit(cli("key", "create", "--org", "acme", "--name", "acme-ci", "--role", "VALIDATE"), 0);
        String org = "(SELECT id FROM fhir_validator_schema.organisations WHERE name_short = 'acme')";
        String key = "(SELECT id FROM fhir_validator_schema.api_keys WHERE name = 'acme-ci')";
        String insert = "INSERT INTO fhir_validator_schema.validation_usage (started_at, request_id, api_key_id, api_key_name, "
                + "org_id, profile_count, request_bytes, outcome, http_status, total_ms, queue_ms, context_ms, validate_ms, "
                + "cpu_time_ns, allocated_bytes) VALUES ";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             java.sql.Statement st = c.createStatement()) {
            st.execute(insert + "(now(), 'a', " + key + ", 'acme-ci', " + org + ", 0, 1, 'valid', 200, 10, 0, 0, 8, 1000, 100), "
                    + "(now(), 'b', " + key + ", 'acme-ci', " + org + ", 0, 1, 'valid', 200, 20, 0, 0, 18, 2000, 200), "
                    + "(now(), 'c', " + key + ", 'acme-ci', " + org + ", 0, 1, 'invalid', 200, 30, 0, 0, 28, -1, -1), "
                    + "(now(), 'd', NULL, NULL, '" + nzi.fhir.validator.core.Constant.SYSTEM_ORG_ID + "', 0, 1, 'valid', 200, 99, 0, 0, 90, 5, 5)");
            // Code review: --since filters on when the request started, not when its batch was inserted.
            st.execute(insert + "('2026-01-01T23:59:59.9Z', 'e', " + key + ", 'acme-ci', " + org
                    + ", 0, 1, 'valid', 200, 1000, 0, 0, 999, 7, 7)");
        }
        for (String[] scope : new String[][]{{"--org", "acme"}, {"--key", "acme-ci"}}) {
            Run r = cli("usage", "summary", scope[0], scope[1], "--since", "2026-01-02T00:00:00Z");
            assertExit(r, 0);
            JsonObject s = r.json();
            assertEquals(3, s.getLong("validations"), r.out());
            assertEquals(new JsonObject().put("invalid", 1L).put("valid", 2L), s.getJsonObject("byOutcome"));
            assertEquals(60, s.getLong("totalMs"));
            assertEquals(20.0, s.getDouble("avgMs"));
            assertEquals(54, s.getLong("validateMs"));
            assertEquals(3000, s.getLong("cpuTimeNs"), "-1 counts as 0");
            assertEquals(300, s.getLong("allocatedBytes"));
        }
        assertExit(cli("usage", "summary", "--org", "acme", "--key", "acme-ci"), 2);
        assertExit(cli("usage", "summary"), 2);
        assertExit(cli("usage", "summary", "--org", "acme", "--since", "yesterday"), 2);
        assertExit(cli("usage", "summary", "--org", "nobody"), 3);
    }

    private HttpResponse<Buffer> validate(String key) throws Exception {
        return await(AppHarness.as(app.client().post(app.port(), "localhost", "/R4/validate"), key)
                .putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(TestPackages.minimalPatient())));
    }

    private int status(String key) {
        try {
            return validate(key).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void accessCommandsCommitAndReachARunningInstance() throws Exception {
        assertExit(createAcme(), 0);
        app = await(AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                POSTGRES_USERNAME, POSTGRES_PASSWORD)));
        await(app.awaitReady(90_000));
        var services = app.bootstrap().services();
        await(TestPackages.includePackage(services, TestPackages.profilePackage("cli.pkg", "1.0.0"), "cli.pkg", "1.0.0"));

        Run share = cli("package", "share", "--ig", "cli.pkg#1.0.0", "--shareable", "false");
        assertExit(share, 0);
        assertEquals(new JsonObject().put("name", "cli.pkg").put("version", "1.0.0").put("owner", "system")
                .put("shareable", false), share.json());
        eventually(10_000, "the package change reaches the running instance",
                () -> !services.configSync().catalog().current().access("cli.pkg#1.0.0").shareable());

        Run transfer = cli("snapshot", "transfer", "--fhir-version", "R4", "--ig", "cli.pkg#1.0.0", "--to", "acme");
        assertExit(transfer, 0);
        assertEquals("acme", transfer.json().getString("owner"));
        assertEquals("cli.pkg#1.0.0", transfer.json().getString("igPackage"));
        eventually(10_000, "the snapshot change reaches the running instance",
                () -> "acme".equals(services.configSync().pinnedAccess(SupportedFhirVersion.R4).get("cli.pkg#1.0.0").ownerShortName()));

        assertExit(cli("package", "share", "--ig", "cli.none#1.0.0", "--shareable", "true"), 3);
        assertExit(cli("package", "transfer", "--ig", "cli.pkg#1.0.0", "--to", "nobody"), 2);
        assertExit(cli("snapshot", "share", "--fhir-version", "R4", "--ig", "cli.pkg"), 2);
        assertExit(cli("package", "share", "--ig", "cli.pkg#1.0.0"), 2);
    }

    /** Spec 009 (T031, FR-032): the command line changes a context that is inactive because R4 isn't served. */
    @Test
    void snapshotCommandsWorkOnAnInactiveContext() throws Exception {
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                POSTGRES_USERNAME, POSTGRES_PASSWORD);
        app = await(AppHarness.start(vertx, config));
        await(app.awaitReady(90_000));
        await(TestPackages.includePackage(app.bootstrap().services(), TestPackages.profilePackage("cli.pkg", "1.0.0"),
                "cli.pkg", "1.0.0"));
        await(app.stop());
        app = await(AppHarness.start(vertx, config.copy().put("fhir", new JsonObject().put("enabledVersions", "R5"))));
        await(app.awaitReady(90_000));
        Run share = cli("snapshot", "share", "--fhir-version", "R4", "--ig", "cli.pkg#1.0.0", "--shareable", "false");
        assertExit(share, 0);
        assertEquals(false, share.json().getBoolean("shareable"));
        var rows = await(app.bootstrap().services().pool().query("SELECT is_active, is_shareable FROM "
                + "fhir_validator_schema.fhir_validator_snapshots WHERE ig_package = 'cli.pkg#1.0.0'").execute());
        var row = rows.iterator().next();
        assertFalse(row.getBoolean(0), "still inactive");
        assertFalse(row.getBoolean(1), "the change is stored");
    }
}
