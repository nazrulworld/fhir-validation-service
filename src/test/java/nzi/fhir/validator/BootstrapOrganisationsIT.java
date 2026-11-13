package nzi.fhir.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.LogCapture;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 008 US2 (FR-025, FR-026, FR-028, SC-009, SC-010): at every start the system and trial organisations exist with
 * their well-known ids, the admin and trial validator keys are installed under their fixed ids, restarting changes
 * nothing, and clashes fail the start loudly.
 */
class BootstrapOrganisationsIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema";

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

    private PgConnectOptions freshDatabase() throws SQLException {
        String name = "boot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return new PgConnectOptions()
                .setHost(getPostgresHost()).setPort(getPostgresPort())
                .setDatabase(name).setUser(POSTGRES_USERNAME).setPassword(POSTGRES_PASSWORD);
    }

    private static JsonObject config(PgConnectOptions db) {
        return AppHarness.baseConfig(db.getHost(), db.getPort(), db.getDatabase(), db.getUser(), db.getPassword());
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

    private static <T> T join(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    /** Starts the service and waits for its warm-up (migration, seeding, validators). */
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

    /** The warm-up failure of a start that must fail. */
    private Throwable warmUpFailure(JsonObject config) throws Exception {
        AppHarness app = join(AppHarness.start(vertx, config));
        try {
            ExecutionException e = assertThrows(ExecutionException.class, () -> join(app.bootstrap().warmUp()));
            return e.getCause();
        } finally {
            join(app.stop());
        }
    }

    private static HttpResponse<Buffer> validate(AppHarness app, String auth) throws Exception {
        return join(app.client().post(app.port(), "localhost", "/R4/validate").putHeader("Authorization", auth)
                .putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(TestPackages.minimalPatient())));
    }

    private static final String KEYS = "SELECT id || '|' || name || '|' || role || '|' || owner || '|' "
            + "|| coalesce(to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), 'never') || '|' "
            + "|| (revoked_at IS NULL) FROM " + S + ".api_keys ORDER BY id";

    @Test
    void wellKnownOrganisationsAndKeysExistAfterStartup() throws Exception {
        PgConnectOptions db = freshDatabase();
        AppHarness app = startReady(config(db));
        try {
            assertEquals(List.of(Constant.SYSTEM_ORG_ID + "|system|FHIR Validation Service|SYSTEM|true",
                            Constant.TRIAL_ORG_ID + "|trial|Free trial validation service|NON_COMMERCIAL|true"),
                    column(db, "SELECT id || '|' || name_short || '|' || name_long || '|' || type || '|' || is_active FROM "
                            + S + ".organisations ORDER BY id"));
            assertEquals(List.of(
                    Constant.SYS_ORG_ADMIN_API_KEY_ID + "|system administrator|ADMIN|" + Constant.SYSTEM_ORG_ID
                            + "|2099-12-30T23:59:59Z|true",
                    Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "|trial validator|VALIDATE|" + Constant.TRIAL_ORG_ID
                            + "|2099-12-30T23:59:59Z|true"), column(db, KEYS));
        } finally {
            join(app.stop());
        }
    }

    @Test
    void trialKeyValidatesButIsForbiddenOnAdminPaths() throws Exception {
        PgConnectOptions db = freshDatabase();
        AppHarness app = startReady(config(db));
        try {
            join(app.awaitReady(90_000));
            HttpResponse<Buffer> ok = validate(app, AppHarness.VALIDATOR_AUTH);
            assertEquals(200, ok.statusCode(), ok.bodyAsString());
            HttpResponse<Buffer> admin = join(AppHarness.asValidator(app.client().post(app.port(), "localhost", "/R4/validator"))
                    .sendJsonObject(new JsonObject().put("igPackageId", "x.ig").put("igPackageVersion", "1.0.0")));
            assertEquals(403, admin.statusCode(), admin.bodyAsString());
        } finally {
            join(app.stop());
        }
    }

    @Test
    void restartIsIdempotentAndRotationReplacesTheHash() throws Exception {
        PgConnectOptions db = freshDatabase();
        String orgs = "SELECT id || '|' || name_short || '|' || name_long || '|' || type || '|' || is_active || '|' "
                + "|| coalesce(updated_at::text, '-') FROM " + S + ".organisations ORDER BY id";
        String hashes = "SELECT id || '|' || encode(key_hash, 'hex') FROM " + S + ".api_keys ORDER BY id";
        JsonObject config = config(db);
        join(startReady(config).stop());
        List<String> orgsBefore = column(db, orgs);
        List<String> keysBefore = column(db, KEYS);
        List<String> hashesBefore = column(db, hashes);

        join(startReady(config).stop());
        assertEquals(orgsBefore, column(db, orgs), "organisations unchanged by a restart");
        assertEquals(keysBefore, column(db, KEYS), "seeded keys unchanged by a restart");
        assertEquals(hashesBefore, column(db, hashes));

        String rotated = "rotated-validator-key-0123456789abcdefghij";
        config.getJsonObject("auth").put("validatorKey", rotated);
        AppHarness app = startReady(config);
        try {
            join(app.awaitReady(90_000));
            assertEquals(keysBefore, column(db, KEYS), "rotation keeps the id, name, role, owner and expiry");
            assertEquals(401, validate(app, AppHarness.VALIDATOR_AUTH).statusCode(), "the old trial key is refused");
            assertEquals(200, validate(app, "Bearer " + rotated).statusCode(), "the new trial key works");
        } finally {
            join(app.stop());
        }
    }

    @Test
    void clashingTrialNameFailsStartup() throws Exception {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        // The well-known trial organisation is gone and another organisation took its name.
        execute(db, "DELETE FROM " + S + ".organisations WHERE id = '" + nzi.fhir.validator.core.Constant.TRIAL_ORG_ID + "'");
        execute(db, "INSERT INTO " + S + ".organisations (id, name_short, name_long, type) VALUES ('" + UUID.randomUUID()
                + "', 'trial', 'Someone else', 'COMMERCIAL')");
        Throwable failure = warmUpFailure(config(db));
        assertInstanceOf(StartupException.class, failure, String.valueOf(failure));
        assertTrue(failure.getMessage().contains("'trial'"), failure.getMessage());
    }

    @Test
    void conflictingSeededKeyFailsWithClearMessage() throws Exception {
        PgConnectOptions db = freshDatabase();
        DatabaseService.flyway(db).migrate();
        // Other keys already hold the seeded admin key's value and name.
        String adminHash = java.util.HexFormat.of().formatHex(
                nzi.fhir.validator.core.security.ApiKeyRepository.hash(AppHarness.ADMIN_KEY));
        execute(db, "INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('bootstrap', '\\x" + adminHash
                + "', 'ADMIN'), ('system administrator', '\\x02', 'ADMIN')");
        Throwable failure = warmUpFailure(config(db));
        assertInstanceOf(StartupException.class, failure, String.valueOf(failure));
        assertTrue(failure.getMessage().contains("system administrator"), failure.getMessage());
        assertTrue(failure.getMessage().contains("api_keys_key_hash_key") || failure.getMessage().contains("api_keys_name_key"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(AppHarness.ADMIN_KEY), "the secret is never in the message");
        // Code review: revoking frees neither the name nor the value, so the advice must not suggest it.
        assertFalse(failure.getMessage().contains("revoke that key"), failure.getMessage());
        assertTrue(failure.getMessage().contains("then restart"), failure.getMessage());
    }

    @Test
    void malformedTrialKeyFailsAtConfigurationLoad() {
        JsonObject config = AppHarness.baseConfig("localhost", 5432, "x", "u", "p");
        config.getJsonObject("auth").put("validatorKey", "short");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ApplicationConfig.of(config));
        assertTrue(e.getMessage().contains("FV_VALIDATOR_API_KEY"), e.getMessage());
        assertFalse(e.getMessage().contains("short"), "the value is never in the message");

        config.getJsonObject("auth").put("validatorKey", AppHarness.ADMIN_KEY);
        e = assertThrows(IllegalStateException.class, () -> ApplicationConfig.of(config));
        assertTrue(e.getMessage().contains("must differ from FV_ADMIN_API_KEY"), e.getMessage());
    }

    /**
     * Code review regression: an instance without the variables (e.g. a worker that isn't given FV_ADMIN_API_KEY) must
     * not revoke the keys other instances rely on. Seeded keys are revoked by name, and stay revoked until the configured
     * value changes.
     */
    @Test
    void anUnsetVariableNeverRevokesAndARevocationLastsUntilTheValueChanges() throws Exception {
        PgConnectOptions db = freshDatabase();
        JsonObject config = config(db);
        join(startReady(config).stop());
        JsonObject worker = config(db);
        worker.getJsonObject("auth").remove("validatorKey");
        worker.getJsonObject("auth").remove("bootstrapAdminKey");
        AppHarness app = startReady(worker);
        try {
            join(app.awaitReady(90_000));
            assertEquals(200, validate(app, AppHarness.VALIDATOR_AUTH).statusCode(), "still valid on every instance");
            assertEquals(List.of("true", "true"), column(db, "SELECT (revoked_at IS NULL)::text FROM " + S
                    + ".api_keys WHERE id IN ('" + Constant.SYS_ORG_ADMIN_API_KEY_ID + "', '"
                    + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "')"));
            join(app.bootstrap().services().apiKeys().revokeNamed(Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME));
            assertEquals(List.of("false"), column(db, "SELECT (revoked_at IS NULL)::text FROM " + S
                    + ".api_keys WHERE id = '" + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'"), "revoked by name");
        } finally {
            join(app.stop());
        }
        AppHarness again = startReady(config);
        try {
            join(again.awaitReady(90_000));
            assertEquals(401, validate(again, AppHarness.VALIDATOR_AUTH).statusCode(),
                    "code review: an instance with the same (leaked) value doesn't bring the revoked key back");
        } finally {
            join(again.stop());
        }
        String rotated = "rotated-validator-key-0123456789abcdefghij";
        config.getJsonObject("auth").put("validatorKey", rotated);
        AppHarness rotatedApp = startReady(config);
        try {
            join(rotatedApp.awaitReady(90_000));
            assertEquals(200, validate(rotatedApp, "Bearer " + rotated).statusCode(), "a new value un-revokes it");
        } finally {
            join(rotatedApp.stop());
        }
    }

    @Test
    void missingTrialKeyLogsAWarning() throws Exception {
        PgConnectOptions db = freshDatabase();
        JsonObject config = config(db);
        config.getJsonObject("auth").remove("validatorKey");
        try (LogCapture logs = LogCapture.start()) {
            join(startReady(config).stop());
            // Spec 008 US2/AC4: the warning says validation is key-only and no trial key is installed.
            assertTrue(logs.events().stream().anyMatch(e -> e.message().contains("FV_VALIDATOR_API_KEY is not set")
                    && e.message().contains("validation is key-only")
                    && e.message().contains("installs no 'trial validator' key")), "key-only warning");
        }
        assertEquals(List.of("0"), column(db, "SELECT count(*)::text FROM " + S + ".api_keys WHERE id = '"
                + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'"));
    }
}
