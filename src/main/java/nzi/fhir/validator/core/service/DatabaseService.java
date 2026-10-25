package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.core.concurrency.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Applies Flyway migrations on the package worker pool. If the database is not reachable yet, retries with
 * exponential backoff (1 s to 30 s) until it is; the instance stays not-ready meanwhile.
 *
 * @author Md Nazrul Islam
 */
public final class DatabaseService {
    private static final Logger logger = LogManager.getLogger(DatabaseService.class);
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private DatabaseService() {
    }

    public static String jdbcUrl(PgConnectOptions options) {
        String ssl = options.getSslMode() == null ? "prefer" : options.getSslMode().name().toLowerCase().replace('_', '-');
        return "jdbc:postgresql://%s:%d/%s?sslmode=%s".formatted(options.getHost(), options.getPort(), options.getDatabase(), ssl);
    }

    public static FluentConfiguration flywayBuilder(PgConnectOptions options) {
        return Flyway.configure()
                .dataSource(jdbcUrl(options), options.getUser(), options.getPassword())
                .schemas(DB_POSTGRES_SCHEMA_NAME)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0");
    }

    public static Flyway flyway(PgConnectOptions options) {
        return flywayBuilder(options).load();
    }

    /**
     * Migrates the database, retrying until it succeeds.
     */
    public static Future<Void> migrate(Vertx vertx, Executors executors, PgConnectOptions options) {
        Promise<Void> promise = Promise.promise();
        attempt(vertx, executors, options, INITIAL_BACKOFF_MS, promise);
        return promise.future();
    }

    private static void attempt(Vertx vertx, Executors executors, PgConnectOptions options, long backoffMs, Promise<Void> promise) {
        executors.packages().<Void>executeBlocking(() -> {
            flyway(options).migrate();
            return null;
        }, false).onComplete(ar -> {
            if (ar.succeeded()) {
                logger.info("Database migration completed");
                promise.complete();
            } else {
                logger.warn("Database migration failed ({}); retrying in {} ms", rootMessage(ar.cause()), backoffMs);
                vertx.setTimer(backoffMs, id -> attempt(vertx, executors, options, Math.min(backoffMs * 2, MAX_BACKOFF_MS), promise));
            }
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
