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

    @Test
    void secretsNeverReachTheLogs(Vertx vertx, VertxTestContext ctx) {
        LogCapture logs = LogCapture.start();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, PASSWORD);
        config.getJsonObject("auth").put("bootstrapAdminKey", ADMIN_KEY);
        ApplicationConfig parsed = ApplicationConfig.of(config);
        assertFalse(parsed.toString().contains(PASSWORD));
        assertFalse(parsed.toString().contains(ADMIN_KEY));
        // Wrong password on purpose: the startup path logs connection failures and retries.
        AppHarness.start(vertx, config)
                .compose(app -> vertx.timer(4_000).compose(t -> app.stop()))
                .onComplete(ar -> ctx.verify(() -> {
                    logs.close();
                    List<String> lines = logs.events().stream().map(e -> e.message()).toList();
                    assertFalse(lines.isEmpty());
                    for (String line : lines) {
                        for (int i = 0; i + 6 <= PASSWORD.length(); i++) {
                            assertFalse(line.contains(PASSWORD.substring(i, i + 6)), "password fragment logged: " + line);
                        }
                        for (int i = 0; i + 6 <= ADMIN_KEY.length(); i++) {
                            assertFalse(line.contains(ADMIN_KEY.substring(i, i + 6)), "admin key fragment logged: " + line);
                        }
                    }
                    ctx.completeNow();
                }));
    }
}
