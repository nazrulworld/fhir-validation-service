package nzi.fhir.validator.core.config;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

/**
 * @author Md Nazrul Islam
 */
public class PgConfig {
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(PgConfig.class);

    public static PgConnectOptions createPgOptions() {

        return new PgConnectOptions()
                .setHost(ApplicationConfig.get( "pg.host", "localhost"))
                .setPort(Integer.parseInt(ApplicationConfig.get("pg.port", "54329")))
                .setDatabase(ApplicationConfig.get("pg.database", "fhir_validator"))
                .setUser(ApplicationConfig.get("pg.user", "postgres"))
                .setPassword(ApplicationConfig.get("pg.password", "Test1234"))
                .setCachePreparedStatements(true);
    }
    public static PoolOptions createPoolOptions() {

        return new PoolOptions()
                .setMaxSize(ApplicationConfig.get("pg.pool.size", "15") != null ? Integer.parseInt(ApplicationConfig.get("pg.pool.size", "15")) : 15)
                .setIdleTimeout(300000)
                .setConnectionTimeout(5000);
    }

    public static Pool createPgPool(Vertx vertx) {
        return createPgPool(vertx, createPoolOptions(), createPgOptions());
    }

    public static Pool createPgPool(Vertx vertx, PoolOptions poolOptions) {
        return createPgPool(vertx, poolOptions, createPgOptions());
    }
    public static Pool createPgPool(Vertx vertx, PoolOptions poolOptions, PgConnectOptions connectOptions) {

        logger.info("Connecting to PostgresSQL at {}:{}***@{}:{}/{}",
                connectOptions.getUser(),
                connectOptions.getPassword().substring(0, connectOptions.getPassword().length() -3),
                connectOptions.getHost(),
                connectOptions.getPort(),
                connectOptions.getDatabase());
        return PgBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }
}
