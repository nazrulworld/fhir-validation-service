package nzi.fhir.validator.core.config;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

/**
 * PostgreSQL connection settings derived from {@link ApplicationConfig}. Never logs credentials.
 *
 * @author Md Nazrul Islam
 */
public final class PgConfig {
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(PgConfig.class);

    private PgConfig() {
    }

    public static PgConnectOptions createPgOptions(ApplicationConfig config) {
        PgConnectOptions options = new PgConnectOptions()
                .setHost(config.pgHost())
                .setPort(config.pgPort())
                .setDatabase(config.pgDatabase())
                .setUser(config.pgUser())
                .setCachePreparedStatements(true)
                .setSslMode(sslMode(config.pgSslMode()));
        if (config.pgPassword() != null) {
            options.setPassword(config.pgPassword());
        }
        if (options.getSslMode() == SslMode.PREFER || options.getSslMode() == SslMode.REQUIRE) {
            // Encrypt the connection without hostname verification; verify-full turns verification on.
            options.setTrustAll(true);
        }
        return options;
    }

    private static SslMode sslMode(String mode) {
        return switch (mode == null ? "prefer" : mode) {
            case "disable" -> SslMode.DISABLE;
            case "require" -> SslMode.REQUIRE;
            case "verify-full" -> SslMode.VERIFY_FULL;
            default -> SslMode.PREFER;
        };
    }

    public static PoolOptions createPoolOptions(ApplicationConfig config) {
        return new PoolOptions()
                .setMaxSize(config.pgPoolSize())
                .setIdleTimeout(300000)
                .setConnectionTimeout(5000);
    }

    public static Pool createPgPool(Vertx vertx, ApplicationConfig config) {
        PgConnectOptions connectOptions = createPgOptions(config);
        logger.info("Connecting to PostgreSQL at {}:{}/{}", connectOptions.getHost(), connectOptions.getPort(), connectOptions.getDatabase());
        return PgBuilder.pool()
                .with(createPoolOptions(config))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }
}
