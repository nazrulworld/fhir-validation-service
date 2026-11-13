package nzi.fhir.validator.core.config;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B18 regression / FR-030: neither the database password nor the admin key appears in any log line, not even in
 * connection-failure messages.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class NoSecretsInLogsTest extends BaseTestContainer {
    private static final String PASSWORD = "Qx7Zr9Wk2Lp4Nm8T";
    private static final String ADMIN_KEY = "Kz8Hq3Vn6Tb1Wx5Rj9Pd2Lc7Mf4Gs0Ya";
    private static final String VALIDATOR_KEY = "Vb3Jm8Qx1Rz6Tn4Hw9Kc2Pf7Ld5Gy0Sa";

    @Test
    void secretsNeverReachTheLogs(Vertx vertx, VertxTestContext ctx) {
        LogCapture logs = LogCapture.start();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, PASSWORD);
        config.getJsonObject("auth").put("bootstrapAdminKey", ADMIN_KEY).put("validatorKey", VALIDATOR_KEY);
        ApplicationConfig parsed = ApplicationConfig.of(config);
        assertFalse(parsed.toString().contains(PASSWORD));
        assertFalse(parsed.toString().contains(ADMIN_KEY));
        assertFalse(parsed.toString().contains(VALIDATOR_KEY), "spec 008: FV_VALIDATOR_API_KEY is redacted");
        // Wrong password on purpose: the startup path logs connection failures and retries.
        AppHarness.start(vertx, config)
                .compose(app -> vertx.timer(4_000).compose(t -> app.stop()))
                .onComplete(ar -> ctx.verify(() -> {
                    logs.close();
                    List<String> lines = logs.events().stream().map(e -> e.message()).toList();
                    assertFalse(lines.isEmpty());
                    for (String line : lines) {
                        // Any 4-character fragment counts (T087); the secrets contain no ordinary words.
                        for (int i = 0; i + 4 <= PASSWORD.length(); i++) {
                            assertFalse(line.contains(PASSWORD.substring(i, i + 4)), "password fragment logged: " + line);
                        }
                        for (int i = 0; i + 4 <= ADMIN_KEY.length(); i++) {
                            assertFalse(line.contains(ADMIN_KEY.substring(i, i + 4)), "admin key fragment logged: " + line);
                        }
                        for (int i = 0; i + 4 <= VALIDATOR_KEY.length(); i++) {
                            assertFalse(line.contains(VALIDATOR_KEY.substring(i, i + 4)), "validator key fragment logged: " + line);
                        }
                    }
                    ctx.completeNow();
                }));
    }

    /**
     * Spec 005 FR-024, FR-026: the command line logs every change at INFO ({@code cli: ...}) and never a key value,
     * whether generated or read from stdin.
     */
    @Test
    void commandLineLogsChangesButNeverKeys() {
        resetSchema();
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("FV_PROFILE", "dev");
        env.put("FV_PG_HOST", getPostgresHost());
        env.put("FV_PG_PORT", String.valueOf(getPostgresPort()));
        env.put("FV_PG_DATABASE", POSTGRES_DATABASE);
        env.put("FV_PG_USER", POSTGRES_USERNAME);
        env.put("FV_PG_PASSWORD", POSTGRES_PASSWORD);
        env.put("FV_PG_SSL_MODE", "disable");
        String stdinKey = "stdin-key-0123456789abcdefghijklmnopqrstu";
        LogCapture logs = LogCapture.start();
        java.io.ByteArrayOutputStream generated = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
        try {
            assertEquals(0, run(env, sink, "", "org", "create", "--name-short", "logorg", "--name-long", "Log Org", "--type", "COMMERCIAL"));
            assertEquals(0, run(env, new java.io.PrintStream(generated), "", "key", "create", "--org", "logorg", "--name", "log-gen",
                    "--role", "VALIDATE"));
            assertEquals(0, run(env, sink, stdinKey + "\n", "key", "create", "--org", "logorg", "--name", "log-stdin", "--role",
                    "ADMIN", "--key-stdin"));
            assertEquals(0, run(env, sink, "", "org", "deactivate", "--name-short", "logorg"));
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
                 java.sql.Statement st = c.createStatement()) {
                st.execute("INSERT INTO fhir_validator_schema.fhir_implementation_guides (ig_package_id, ig_package_version, "
                        + "ig_package_meta, content_raw) VALUES ('log.pkg', '1.0.0', '{}', '\\x01')");
            } catch (java.sql.SQLException e) {
                throw new AssertionError(e);
            }
            assertEquals(0, run(env, sink, "", "package", "share", "--ig", "log.pkg#1.0.0", "--shareable", "false"));
        } finally {
            logs.close();
        }
        String generatedKey = new JsonObject(generated.toString()).getString("key");
        List<String> lines = logs.events().stream().map(e -> e.message()).toList();
        for (String line : lines) {
            for (String key : List.of(generatedKey, stdinKey)) {
                for (int i = 0; i + 8 <= key.length(); i++) {
                    assertFalse(line.contains(key.substring(i, i + 8)), "key fragment logged: " + line);
                }
            }
        }
        // LogCapture sees an event once per logger it is attached to (additivity): compare distinct lines.
        List<String> cli = lines.stream().filter(l -> l.startsWith("cli: ")).distinct().toList();
        assertEquals(1, cli.stream().filter(l -> l.startsWith("cli: org create name_short=logorg")).count(), cli.toString());
        assertEquals(1, cli.stream().filter(l -> l.startsWith("cli: key create name=log-gen org=logorg")).count(), cli.toString());
        assertEquals(1, cli.stream().filter(l -> l.startsWith("cli: key create name=log-stdin org=logorg")).count(), cli.toString());
        assertEquals(1, cli.stream().filter(l -> l.startsWith("cli: org deactivate name_short=logorg")).count(), cli.toString());
        assertEquals(1, cli.stream().filter(l -> l.startsWith("cli: package share ig=log.pkg#1.0.0 owner=system shareable=false"))
                .count(), cli.toString());
    }

    private static int run(java.util.Map<String, String> env, java.io.PrintStream out, String stdin, String... args) {
        return nzi.fhir.validator.cli.AdminCli.run(args, out, new java.io.PrintStream(new java.io.ByteArrayOutputStream()), env,
                new java.io.ByteArrayInputStream(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Spec 009 (T039, FR-021, FR-022, SC-008): a disabled start and the switch back log neither the configured key values
     * nor the generated random ones.
     */
    @Test
    void accessControlModeChangesNeverLogKeyValues(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        java.util.List<String> generated = new java.util.concurrent.CopyOnWriteArrayList<>();
        nzi.fhir.validator.core.security.AclModeStartup.setGeneratedValueListenerForTests(generated::add);
        LogCapture logs = LogCapture.start();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME,
                POSTGRES_PASSWORD);
        config.put("fhir", new JsonObject().put("enabledVersions", "R4"));
        config.getJsonObject("auth").put("bootstrapAdminKey", ADMIN_KEY).put("validatorKey", VALIDATOR_KEY);
        JsonObject disabled = config.copy();
        disabled.getJsonObject("auth").put("globalDisabledAclSecurity", true);
        JsonObject enabled = config.copy();
        enabled.getJsonObject("auth").put("aclModeChangeConfirmed", true);
        AppHarness.start(vertx, disabled)
                .compose(app -> app.bootstrap().warmUp().compose(v -> app.stop()))
                .compose(v -> AppHarness.start(vertx, enabled))
                .compose(app -> app.bootstrap().warmUp().compose(v -> app.stop()))
                .onComplete(ar -> ctx.verify(() -> {
                    logs.close();
                    nzi.fhir.validator.core.security.AclModeStartup.setGeneratedValueListenerForTests(null);
                    assertTrue(ar.succeeded(), String.valueOf(ar.cause()));
                    assertEquals(2, generated.size(), "two random values were generated");
                    List<String> lines = logs.events().stream().map(e -> e.message()).toList();
                    assertTrue(lines.stream().anyMatch(l -> l.contains("re-synchronised")), "the switch back was logged");
                    for (String line : lines) {
                        for (String secret : List.of(ADMIN_KEY, VALIDATOR_KEY)) {
                            for (int i = 0; i + 4 <= secret.length(); i++) {
                                assertFalse(line.contains(secret.substring(i, i + 4)), "configured key fragment logged: " + line);
                            }
                        }
                        // Random values may share short fragments with ordinary text by chance: 8 characters can't.
                        for (String value : generated) {
                            for (int i = 0; i + 8 <= value.length(); i++) {
                                assertFalse(line.contains(value.substring(i, i + 8)), "generated key fragment logged: " + line);
                            }
                        }
                    }
                    ctx.completeNow();
                }));
    }
}
