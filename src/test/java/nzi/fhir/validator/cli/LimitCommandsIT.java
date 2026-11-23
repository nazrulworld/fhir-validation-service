package nzi.fhir.validator.cli;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 014 US3 and US5 (T025, T036): {@code limit set | remove | list} (contracts/operations.md §2). */
class LimitCommandsIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema.";

    record Run(int exit, String out, String err) {
        JsonObject json() {
            return new JsonObject(out);
        }
    }

    @BeforeEach
    void freshDatabase() {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
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

    static Run cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = AdminCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env(), new ByteArrayInputStream(new byte[0]));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertExit(Run r, int exit) {
        assertEquals(exit, r.exit(), "stdout: " + r.out() + " stderr: " + r.err());
        if (exit != 0) {
            assertEquals("", r.out(), "nothing on stdout on failure");
            assertTrue(r.err().contains("error: "), r.err());
        }
    }

    private static Object sql(String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private static void acmeWithKey() {
        assertExit(cli("org", "create", "--name-short", "acme", "--name-long", "Acme", "--type", "COMMERCIAL"), 0);
        assertExit(cli("key", "create", "--org", "acme", "--name", "acme-a", "--role", "VALIDATE"), 0);
    }

    private static JsonObject org(JsonObject list, String name) {
        JsonArray orgs = list.getJsonArray("organisations");
        for (int i = 0; i < orgs.size(); i++) {
            if (name.equals(orgs.getJsonObject(i).getString("name"))) {
                return orgs.getJsonObject(i);
            }
        }
        return fail("no organisation " + name + " in " + list.encodePrettily());
    }

    /** US3 AC1, AC8: the initial caps on a fresh database. */
    @Test
    void listOnAFreshDatabase() {
        Run r = cli("limit", "list");
        assertExit(r, 0);
        JsonObject list = r.json();
        assertEquals(new JsonObject().put("key", 600).put("organisation", 1200), list.getJsonObject("defaults"));
        JsonObject trial = org(list, Constant.TRIAL_ORG_SHORT_NAME);
        assertEquals(60, trial.getInteger("perMinute"));
        assertEquals(new JsonObject().put("perMinute", 60).put("source", "own"), trial.getJsonObject("effective"));
        JsonObject system = org(list, Constant.SYSTEM_ORG_SHORT_NAME);
        assertEquals("not-throttled", system.getJsonObject("effective").getString("source"));
    }

    /** US3: set and remove for every target, with the JSON shapes and the effective cap. */
    @Test
    void setAndRemoveEveryTarget() throws SQLException {
        acmeWithKey();
        Run set = cli("limit", "set", "--key", "acme-a", "--per-minute", "120");
        assertExit(set, 0);
        JsonObject j = set.json();
        assertEquals("key", j.getString("target"));
        assertEquals("acme-a", j.getString("name"));
        assertNotNull(j.getString("id"));
        assertNull(j.getValue("previous"));
        assertEquals(120, j.getInteger("perMinute"));
        assertTrue(j.getBoolean("changed"));
        assertEquals(new JsonObject().put("perMinute", 120).put("source", "own"), j.getJsonObject("effective"));
        assertEquals(120, sql("SELECT rate_limit_per_minute FROM " + S + "api_keys WHERE name = 'acme-a'"));

        Run again = cli("limit", "set", "--key", "acme-a", "--per-minute", "90");
        assertEquals(120, again.json().getInteger("previous"));

        Run remove = cli("limit", "remove", "--key", "acme-a");
        assertExit(remove, 0);
        assertEquals(90, remove.json().getInteger("previous"));
        assertNull(remove.json().getValue("perMinute"));
        assertEquals(new JsonObject().put("perMinute", 600).put("source", "default"), remove.json().getJsonObject("effective"));
        Run removeAgain = cli("limit", "remove", "--key", "acme-a");
        assertExit(removeAgain, 0);
        assertFalse(removeAgain.json().getBoolean("changed"));

        Run orgSet = cli("limit", "set", "--org", "acme", "--per-minute", "7");
        assertExit(orgSet, 0);
        assertEquals("organisation", orgSet.json().getString("target"));
        assertEquals(7, sql("SELECT rate_limit_per_minute FROM " + S + "organisations WHERE name_short = 'acme'"));

        Run defaultKey = cli("limit", "set", "--default-key", "--per-minute", "3");
        assertExit(defaultKey, 0);
        assertEquals("default-key", defaultKey.json().getString("target"));
        assertFalse(defaultKey.json().containsKey("name"));
        assertEquals(600, defaultKey.json().getInteger("previous"));
        assertEquals("3", sql("SELECT value FROM " + S + "service_settings WHERE name = 'throttle_default_key_per_minute'"));
        assertTrue(String.valueOf(sql("SELECT updated_by FROM " + S + "service_settings WHERE name = "
                + "'throttle_default_key_per_minute'")).startsWith("cli:"));

        Run removeDefault = cli("limit", "remove", "--default-org");
        assertExit(removeDefault, 0);
        assertEquals("default-organisation", removeDefault.json().getString("target"));
        assertNull(sql("SELECT value FROM " + S + "service_settings WHERE name = 'throttle_default_org_per_minute'"));
        Run list = cli("limit", "list", "--org", "acme");
        assertExit(list, 0);
        assertEquals(new JsonObject().put("key", 3).putNull("organisation"), list.json().getJsonObject("defaults"));
        assertEquals(1, list.json().getJsonArray("organisations").size());
        JsonObject acme = org(list.json(), "acme");
        JsonObject key = acme.getJsonArray("keys").getJsonObject(0);
        assertEquals("acme-a", key.getString("name"));
        assertEquals(new JsonObject().put("perMinute", 3).put("source", "default"), key.getJsonObject("effective"));
        assertEquals("VALIDATE", key.getString("role"));
        assertFalse(key.getBoolean("revoked"));
    }

    /** US3 AC4, AC6: invalid values and targets. */
    @Test
    void invalidInput() {
        acmeWithKey();
        for (String bad : new String[]{"0", "-1", "1.5", "abc", "1000001", ""}) {
            assertExit(cli("limit", "set", "--key", "acme-a", "--per-minute", bad), 2);
        }
        assertExit(cli("limit", "set", "--key", "acme-a"), 2);
        assertExit(cli("limit", "set", "--per-minute", "5"), 2);
        assertExit(cli("limit", "set", "--key", "acme-a", "--org", "acme", "--per-minute", "5"), 2);
        assertExit(cli("limit", "remove", "--default-key", "--default-org"), 2);
        // contracts/operations.md §2.1: the error names what wasn't found.
        Run noKey = cli("limit", "set", "--key", "no-such-key", "--per-minute", "5");
        assertExit(noKey, 3);
        assertTrue(noKey.err().contains("error: not-found: API key 'no-such-key' not found"), noKey.err());
        Run noOrg = cli("limit", "set", "--org", "no-such-org", "--per-minute", "5");
        assertExit(noOrg, 3);
        assertTrue(noOrg.err().contains("error: not-found: Organisation 'no-such-org' not found"), noOrg.err());
        Run listNoOrg = cli("limit", "list", "--org", "no-such-org");
        assertExit(listNoOrg, 3);
        assertTrue(listNoOrg.err().contains("error: not-found: Organisation 'no-such-org' not found"), listNoOrg.err());
    }

    /** FR-016: the audit line. */
    @Test
    void changesAreLogged() {
        acmeWithKey();
        try (LogCapture logs = LogCapture.start()) {
            assertExit(cli("limit", "set", "--key", "acme-a", "--per-minute", "5"), 0);
            assertExit(cli("limit", "remove", "--default-key"), 0);
            java.util.List<String> lines = logs.events().stream().map(LogCapture.Captured::message)
                    // LogCapture sees an event once per configured logger it passes through (root and nzi.fhir.validator).
                    .filter(m -> m.startsWith("cli: limit")).distinct().toList();
            assertEquals(2, lines.size(), lines.toString());
            assertTrue(lines.get(0).matches("cli: limit set key=acme-a id=[0-9a-f-]{36} old=none new=5"), lines.get(0));
            assertEquals("cli: limit remove default-key=default id=- old=600 new=none", lines.get(1));
        }
    }

    /** US5 AC3, AC4: no cap for the system organisation or its keys; the list shows them as not throttled. */
    @Test
    void systemOrganisationRefused() throws SQLException {
        assertExit(cli("key", "create", "--org", Constant.SYSTEM_ORG_SHORT_NAME, "--name", "sys-v", "--role", "VALIDATE"), 0);
        for (String[] args : new String[][]{
                {"limit", "set", "--org", Constant.SYSTEM_ORG_SHORT_NAME, "--per-minute", "5"},
                {"limit", "set", "--key", "sys-v", "--per-minute", "5"},
                {"limit", "remove", "--org", Constant.SYSTEM_ORG_SHORT_NAME}}) {
            Run r = cli(args);
            assertExit(r, 4);
            assertTrue(r.err().contains("The system organisation and its keys are never rate limited"), r.err());
        }
        assertNull(sql("SELECT rate_limit_per_minute FROM " + S + "api_keys WHERE name = 'sys-v'"));
        Run list = cli("limit", "list", "--org", Constant.SYSTEM_ORG_SHORT_NAME);
        assertExit(list, 0);
        JsonObject system = org(list.json(), Constant.SYSTEM_ORG_SHORT_NAME);
        assertEquals("not-throttled", system.getJsonObject("effective").getString("source"));
        JsonObject key = system.getJsonArray("keys").getJsonObject(0);
        assertEquals("sys-v", key.getString("name"));
        assertEquals(new JsonObject().putNull("perMinute").put("source", "not-throttled"), key.getJsonObject("effective"));
    }
}
