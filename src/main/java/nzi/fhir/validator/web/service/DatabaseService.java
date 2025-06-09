package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.web.config.PgConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;

import static nzi.fhir.validator.web.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * @author Md Nazrul Islam
 */
public class DatabaseService {
    private static final Logger logger = LogManager.getLogger(DatabaseService.class);
    private final Vertx vertx;

    private DatabaseService(Vertx vertx) {
        this.vertx = vertx;
    }

    public static Future<DatabaseService> start(Vertx vertx) {
        DatabaseService service = new DatabaseService(vertx);
        return service.migrateDatabase()
                .map(service);
    }

    private Future<Void> migrateDatabase() {
        Promise<Void> promise = Promise.promise();
        PgConnectOptions dbOptions = PgConfig.createPgOptions();
        
        vertx.executeBlocking(blocking -> {
            try {
                String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?DatabaseType=postgresql",
                        dbOptions.getHost(),
                        dbOptions.getPort(),
                        dbOptions.getDatabase());

                Flyway flyway = Flyway.configure()
                        .dataSource(
                                jdbcUrl,
                                dbOptions.getUser(),
                                dbOptions.getPassword()
                        )
                        .schemas(DB_POSTGRES_SCHEMA_NAME)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load();

                try {
                    flyway.migrate();
                    logger.info("Database migration completed successfully with Flyway");
                    blocking.complete();
                } catch (Exception e) {
                    logger.error("Flyway migration failed: {}", e.getMessage(), e);
                    blocking.fail(new RuntimeException("Flyway migration failed", e));
                }
            } catch (Exception e) {
                logger.error("Failed to configure Flyway: {}", e.getMessage(), e);
                blocking.fail(new RuntimeException("Failed to configure Flyway", e));
            }
        }).onComplete(result -> {
            if (result.succeeded()) {
                promise.complete();
            } else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }
}