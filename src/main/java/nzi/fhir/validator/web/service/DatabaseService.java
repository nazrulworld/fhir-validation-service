package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.web.config.PgConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;

import java.util.concurrent.Callable;

/**
 * @author Md Nazrul Islam
 */
public class DatabaseService {
    private static final Logger logger = LogManager.getLogger(DatabaseService.class);
    private final Vertx vertx;

    private DatabaseService(Vertx vertx) {
        this.vertx = vertx;
        migrateDatabase();
    }
    public static DatabaseService start(Vertx vertx){
        return new DatabaseService(vertx);
    }
    private void migrateDatabase() {
        PgConnectOptions dbOptions = PgConfig.createPgOptions();
        Future<Void> future = vertx.executeBlocking(() -> {
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(
                                "jdbc:postgresql://" + dbOptions.getHost() + ":" + dbOptions.getPort() + "/" + dbOptions.getDatabase(),
                                dbOptions.getUser(),
                                dbOptions.getPassword()
                        )
                        .schemas("public")
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load();
                flyway.migrate();
                return null; // Return null for Void Future
            } catch (Exception e) {
                throw new RuntimeException("Flyway migration failed", e);
            }
        });

        future.onComplete(result -> {
            if (result.succeeded()) {
                logger.info("Database migration completed successfully with Flyway 11.8.1");
            } else {
                logger.error("Failed to initialize database with Flyway: {}", result.cause().getMessage(), result.cause());
                throw new RuntimeException("Database initialization failed", result.cause());
            }
        });
    }
}