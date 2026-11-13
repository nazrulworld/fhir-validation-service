package nzi.fhir.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.core.security.AclModeStartup;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.LogCapture;
import nzi.fhir.validator.testsupport.TestPackages;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 US5 and US6 (T038, T043): the startup access-control step. Disabled mode installs unknown random keys for
 * the fixed callers and stores the mode; a mode change needs {@code FV_ACL_MODE_CHANGE_CONFIRMED}; switching back
 * re-installs the configured keys.
 */
class BootstrapAclModeIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema";
    private static final String ADMIN_HASH = "SELECT encode(key_hash, 'hex') FROM " + S + ".api_keys WHERE id = '"
            + Constant.SYS_ORG_ADMIN_API_KEY_ID + "'";
    private static final String TRIAL_HASH = "SELECT encode(key_hash, 'hex') FROM " + S + ".api_keys WHERE id = '"
            + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'";
    private static final String MODE = "SELECT value || '|' || (updated_by IS NOT NULL) FROM " + S
            + ".service_settings WHERE name = 'acl_security'";

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        startContainers();
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        join(vertx.close());
    }

    // ---- helpers ----

    private PgConnectOptions freshDatabase() throws SQLException {
        String name = "acl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return new PgConnectOptions().setHost(getPostgresHost()).setPort(getPostgresPort())
                .setDatabase(name).setUser(POSTGRES_USERNAME).setPassword(POSTGRES_PASSWORD);
    }

    /** One served version keeps the starts quick; the access-control step doesn't depend on it. */
    private static JsonObject config(PgConnectOptions db, boolean disabled, boolean confirmed) {
        JsonObject config = AppHarness.baseConfig(db.getHost(), db.getPort(), db.getDatabase(), db.getUser(), db.getPassword());
        config.put("fhir", new JsonObject().put("enabledVersions", "R4"));
        config.getJsonObject("auth").put("globalDisabledAclSecurity", disabled).put("aclModeChangeConfirmed", confirmed);
        return config;
    }

    private static Connection jdbc(PgConnectOptions o) throws SQLException {
        return DriverManager.getConnection(DatabaseService.jdbcUrl(o), o.getUser(), o.getPassword());
    }

    private static List<String> column(PgConnectOptions db, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = jdbc(db); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void execute(PgConnectOptions db, String sql) throws SQLException {
        try (Connection c = jdbc(db); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String hash(String key) {
        return HexFormat.of().formatHex(ApiKeyRepository.hash(key));
    }

    private static <T> T join(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private AppHarness startReady(JsonObject config) throws Exception {
        AppHarness app = join(AppHarness.start(vertx, config));
        try {
            join(app.bootstrap().warmUp());
        } catch (Exception e) {
            join(app.stop());
            throw e;
        }
        return app;
    }

    private void startAndStop(JsonObject config) throws Exception {
        join(startReady(config).stop());
    }

    private Throwable warmUpFailure(JsonObject config) throws Exception {
        AppHarness app = join(AppHarness.start(vertx, config));
        try {
            ExecutionException e = assertThrows(ExecutionException.class, () -> join(app.bootstrap().warmUp()));
            return e.getCause();
        } finally {
            join(app.stop());
        }
    }

    private static List<String> messages(LogCapture logs, Level level) {
        return logs.from(AclModeStartup.class.getName()).stream().filter(e -> e.level() == level)
                .map(LogCapture.Captured::message).distinct().toList();
    }

    private static HttpResponse<Buffer> validate(AppHarness app, String key) throws Exception {
        var request = app.client().post(app.port(), "localhost", "/R4/validate")
                .putHeader("Content-Type", "application/fhir+json");
        if (key != null) {
            request.putHeader("Authorization", "Bearer " + key);
        }
        return join(request.sendBuffer(Buffer.buffer(TestPackages.minimalPatient())));
    }

    // ---- US5 ----

    /** US5-1 to US5-3, FR-021 to FR-024: a fresh database starts disabled without confirmation. */
    @Test
    void disabledStartInstallsUnknownKeysAndStoresTheMode() throws Exception {
        PgConnectOptions db = freshDatabase();
        try (LogCapture logs = LogCapture.start()) {
            startAndStop(config(db, true, false));
            assertEquals(List.of("FV_ADMIN_API_KEY is ignored: access control is disabled",
                    "FV_VALIDATOR_API_KEY is ignored: access control is disabled"), messages(logs, Level.INFO));
            assertEquals(List.of("ACCESS CONTROL IS DISABLED (FV_GLOBAL_DISABLED_ACL_SECURITY): no endpoint needs a key; "
                    + "administrative endpoints are open from any network"), messages(logs, Level.WARN));
        }
        assertEquals(List.of(
                Constant.SYS_ORG_ADMIN_API_KEY_ID + "|system administrator|ADMIN|" + Constant.SYSTEM_ORG_ID + "|true",
                Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "|trial validator|VALIDATE|" + Constant.TRIAL_ORG_ID + "|true"),
                column(db, "SELECT id || '|' || name || '|' || role || '|' || owner || '|' || (revoked_at IS NULL) FROM "
                        + S + ".api_keys ORDER BY id"));
        String admin = column(db, ADMIN_HASH).get(0);
        String trial = column(db, TRIAL_HASH).get(0);
        assertNotEquals(hash(AppHarness.ADMIN_KEY), admin, "the configured value is ignored");
        assertNotEquals(hash(AppHarness.VALIDATOR_KEY), trial, "the configured value is ignored");
        assertEquals(List.of("disabled|true"), column(db, MODE));

        startAndStop(config(db, true, false));
        assertNotEquals(admin, column(db, ADMIN_HASH).get(0), "re-randomised at every disabled start");
        assertNotEquals(trial, column(db, TRIAL_HASH).get(0), "re-randomised at every disabled start");
    }

    /** Research R13: a malformed, ignored key value doesn't stop a disabled start; the allowlist is named (FR-030). */
    @Test
    void malformedIgnoredKeyDoesNotStopADisabledStart() throws Exception {
        PgConnectOptions db = freshDatabase();
        JsonObject config = config(db, true, false);
        config.getJsonObject("auth").put("bootstrapAdminKey", "short").put("adminCidrs", "127.0.0.0/8");
        try (LogCapture logs = LogCapture.start()) {
            startAndStop(config);
            assertTrue(messages(logs, Level.WARN).contains("ACCESS CONTROL IS DISABLED (FV_GLOBAL_DISABLED_ACL_SECURITY): "
                    + "no endpoint needs a key; administrative endpoints are limited to FV_AUTH_ADMIN_CIDRS"));
        }
    }

    /**
     * Code review of PR #72: a fixed key revoked while access control is disabled stays revoked across restarts (an
     * operator may revoke the trial validator to stop anonymous validation); its value still changes.
     */
    @Test
    void aRevokedFixedKeyStaysRevokedAcrossDisabledStarts() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, true, false));
        execute(db, "UPDATE " + S + ".api_keys SET revoked_at = now() WHERE id = '" + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'");
        String before = column(db, TRIAL_HASH).get(0);
        AppHarness app;
        try (LogCapture logs = LogCapture.start()) {
            app = startReady(config(db, true, false));
            assertTrue(messages(logs, Level.WARN).contains("The 'trial validator' key is revoked: requests that run as it are "
                    + "refused with 403 while access control is disabled, also after restarts"), messages(logs, Level.WARN).toString());
        }
        try {
            assertEquals(List.of("false"), column(db, "SELECT (revoked_at IS NULL)::text FROM " + S + ".api_keys WHERE id = '"
                    + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'"), "still revoked");
            assertNotEquals(before, column(db, TRIAL_HASH).get(0), "the value still changes");
            HttpResponse<Buffer> refused = validate(app, null);
            assertEquals(403, refused.statusCode(), refused.bodyAsString());
            assertEquals(List.of("1"), column(db, "SELECT count(*)::text FROM " + S + ".revoked_key_hashes"),
                    "the restart recorded no further revoked value");
        } finally {
            join(app.stop());
        }
    }

    // ---- US6 ----

    /** US6-7, FR-029, SC-010: stored disabled + configured enabled without confirmation is refused; nothing changes. */
    @Test
    void switchingBackWithoutConfirmationIsRefused() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, true, false));
        List<String> before = List.of(column(db, ADMIN_HASH).get(0), column(db, TRIAL_HASH).get(0));
        Throwable failure = warmUpFailure(config(db, false, false));
        assertInstanceOf(StartupException.class, failure);
        assertEquals("access control mode change refused: stored mode is disabled, this instance is configured enabled. "
                + "To switch, set FV_ACL_MODE_CHANGE_CONFIRMED=true on the instances being switched (see "
                + "docs/upgrade-4.2.md)", failure.getMessage());
        assertEquals(before, List.of(column(db, ADMIN_HASH).get(0), column(db, TRIAL_HASH).get(0)), "keys unchanged");
        assertEquals(List.of("disabled|true"), column(db, MODE), "mode unchanged");
    }

    /** US6-6, research R10: enabled → disabled without confirmation is refused, and the refused instance never serves. */
    @Test
    void disablingWithoutConfirmationIsRefusedAndTheRefusedInstanceNeverServes() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, false, false));
        String admin = column(db, ADMIN_HASH).get(0);
        AppHarness app = join(AppHarness.start(vertx, config(db, true, false)));
        try {
            ExecutionException e = assertThrows(ExecutionException.class, () -> join(app.bootstrap().warmUp()));
            assertTrue(e.getCause().getMessage().contains("stored mode is enabled, this instance is configured disabled"),
                    e.getCause().getMessage());
            HttpResponse<Buffer> admin503 = join(app.client().post(app.port(), "localhost", "/R4/validator")
                    .sendJsonObject(new JsonObject().put("igPackageId", "x")));
            assertEquals(503, admin503.statusCode(), "the gate never opened: " + admin503.bodyAsString());
            assertEquals(503, validate(app, null).statusCode());
        } finally {
            join(app.stop());
        }
        assertEquals(admin, column(db, ADMIN_HASH).get(0));
        assertEquals(List.of("enabled|true"), column(db, MODE));
    }

    /** US6-1, US6-2, FR-025, FR-026: confirmed switch back re-installs the env keys; disabled-mode work stays reachable. */
    @Test
    void confirmedSwitchBackResynchronisesKeysAndKeepsOwnership() throws Exception {
        PgConnectOptions db = freshDatabase();
        AppHarness open = startReady(config(db, true, false));
        try {
            join(TestPackages.storePackage(open.bootstrap().services(), TestPackages.profilePackage("acl.pkg", "1.0.0")));
            HttpResponse<Buffer> included = join(open.client().post(open.port(), "localhost", "/R4/validator").timeout(60_000)
                    .sendJsonObject(new JsonObject().put("igPackageId", "acl.pkg")));
            assertEquals(200, included.statusCode(), included.bodyAsString());
            assertEquals("system", included.bodyAsJsonObject().getString("owner"));
        } finally {
            join(open.stop());
        }
        AppHarness app;
        try (LogCapture logs = LogCapture.start()) {
            app = startReady(config(db, false, true));
            assertEquals(List.of("API keys re-synchronised from environment", "'system administrator' API key installed "
                    + "(FV_ADMIN_API_KEY)", "'trial validator' API key installed (FV_VALIDATOR_API_KEY)"), messages(logs, Level.INFO));
            assertEquals(List.of("Access control mode changes from disabled to enabled (FV_ACL_MODE_CHANGE_CONFIRMED)"),
                    messages(logs, Level.WARN));
        }
        try {
            assertEquals(hash(AppHarness.ADMIN_KEY), column(db, ADMIN_HASH).get(0));
            assertEquals(hash(AppHarness.VALIDATOR_KEY), column(db, TRIAL_HASH).get(0));
            assertEquals(List.of("enabled|true"), column(db, MODE));
            assertEquals(200, validate(app, AppHarness.VALIDATOR_KEY).statusCode());
            assertEquals(401, validate(app, null).statusCode(), "keys are required again (US6-5)");
            HttpResponse<Buffer> again = join(AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                    .timeout(60_000).sendJsonObject(new JsonObject().put("igPackageId", "acl.pkg")));
            assertEquals(200, again.statusCode(), again.bodyAsString());
            assertEquals("system", again.bodyAsJsonObject().getString("owner"), "still owned by the system organisation");
        } finally {
            join(app.stop());
        }
    }

    /** US6-3, US6-4, FR-027: enabled → enabled is 4.1 behaviour: no re-sync line, an ordinary rotation. */
    @Test
    void enabledToEnabledIsUnchangedIncludingRotation() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, false, false));
        try (LogCapture logs = LogCapture.start()) {
            startAndStop(config(db, false, false));
            assertFalse(messages(logs, Level.INFO).contains("API keys re-synchronised from environment"));
            assertEquals(List.of(), messages(logs, Level.WARN), "no access-control warning");
        }
        String rotated = "rotated-validator-key-0123456789abcdefgh";
        JsonObject config = config(db, false, false);
        config.getJsonObject("auth").put("validatorKey", rotated);
        try (LogCapture logs = LogCapture.start()) {
            startAndStop(config);
            assertFalse(messages(logs, Level.INFO).contains("API keys re-synchronised from environment"));
        }
        assertEquals(hash(rotated), column(db, TRIAL_HASH).get(0), "rotated as in 4.1");
    }

    /** US6-8: the confirmation left set while the mode is unchanged is warned about. */
    @Test
    void confirmationWithAnUnchangedModeIsWarnedAbout() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, false, false));
        try (LogCapture logs = LogCapture.start()) {
            startAndStop(config(db, false, true));
            assertEquals(List.of("FV_ACL_MODE_CHANGE_CONFIRMED is set but the mode is unchanged (enabled); remove it so an "
                    + "accidental mode change is refused"), messages(logs, Level.WARN));
        }
    }

    /** Edge case: switching back with FV_VALIDATOR_API_KEY unset leaves the trial key unusable; the admin key works. */
    @Test
    void switchingBackWithAnUnsetKeyLeavesThatKeyUnusable() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, true, false));
        JsonObject config = config(db, false, true);
        config.getJsonObject("auth").remove("validatorKey");
        AppHarness app;
        try (LogCapture logs = LogCapture.start()) {
            app = startReady(config);
            assertTrue(messages(logs, Level.WARN).contains("FV_VALIDATOR_API_KEY is not set: the 'trial validator' key keeps an "
                    + "unknown value and can't be used until it is set"), messages(logs, Level.WARN).toString());
        }
        try {
            assertEquals(401, validate(app, AppHarness.VALIDATOR_KEY).statusCode());
            assertEquals(200, validate(app, AppHarness.ADMIN_KEY).statusCode());
        } finally {
            join(app.stop());
        }
    }

    /** Edge case: switching back to a revoked admin value logs the error and leaves that key unusable. */
    @Test
    void switchingBackToARevokedValueLeavesThatKeyUnusable() throws Exception {
        PgConnectOptions db = freshDatabase();
        startAndStop(config(db, false, false));
        execute(db, "UPDATE " + S + ".api_keys SET revoked_at = now() WHERE id = '" + Constant.SYS_ORG_ADMIN_API_KEY_ID + "'");
        startAndStop(config(db, true, true));
        AppHarness app;
        try (LogCapture logs = LogCapture.start()) {
            app = startReady(config(db, false, true));
            assertTrue(messages(logs, Level.ERROR).contains("FV_ADMIN_API_KEY is set to a value that was revoked: this instance "
                    + "does not install it, and the stored 'system administrator' key is unchanged. Configure a new value."));
        }
        try {
            HttpResponse<Buffer> r = join(AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                    .sendJsonObject(new JsonObject().put("igPackageId", "x")));
            assertEquals(401, r.statusCode());
            assertEquals(200, validate(app, AppHarness.VALIDATOR_KEY).statusCode());
        } finally {
            join(app.stop());
        }
    }

    /** Instances starting at once with the same mode all start, and one row is stored. */
    @Test
    void concurrentStartsWithTheSameModeAllSucceed() throws Exception {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        List<AppHarness> apps = join(Future.all(AppHarness.start(vertx, config(db, true, false)),
                AppHarness.start(vertx, config(db, true, false)), AppHarness.start(vertx, config(db, true, false))))
                .<AppHarness>list();
        try {
            for (AppHarness a : apps) {
                join(a.bootstrap().warmUp());
            }
            assertEquals(List.of("disabled|true"), column(db, MODE));
        } finally {
            for (AppHarness a : apps) {
                join(a.stop());
            }
        }
    }

    /** Code review of PR #72: any failure of the access-control step is fatal (exit 1), never a stuck, gated instance. */
    @Test
    @SuppressWarnings("unchecked")
    void aFailingAccessControlStepIsFatal() {
        io.vertx.sqlclient.Pool pool = org.mockito.Mockito.mock(io.vertx.sqlclient.Pool.class);
        org.mockito.Mockito.doReturn(Future.failedFuture(new IllegalStateException("connection reset")))
                .when(pool).withTransaction(org.mockito.ArgumentMatchers.any(java.util.function.Function.class));
        nzi.fhir.validator.core.config.ApplicationConfig cfg = nzi.fhir.validator.core.config.ApplicationConfig.of(
                AppHarness.baseConfig("localhost", 5432, "x", "u", "p"));
        Future<?> result = AclModeStartup.run(pool, new ApiKeyRepository(pool), cfg,
                new nzi.fhir.validator.core.security.ApiKeyAuthHandler(new ApiKeyRepository(pool), 1000));
        assertTrue(result.failed());
        assertInstanceOf(StartupException.class, result.cause());
        assertEquals("the access-control startup step failed: connection reset", result.cause().getMessage());
    }
}
