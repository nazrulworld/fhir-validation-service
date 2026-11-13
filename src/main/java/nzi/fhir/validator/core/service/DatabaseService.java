package nzi.fhir.validator.core.service;

import java.util.List;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import nzi.fhir.validator.core.concurrency.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import nzi.fhir.validator.core.StartupException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.ValidateResult;
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
            migrateOnce(options);
            return null;
        }, false).onComplete(ar -> {
            if (ar.succeeded()) {
                logger.info("Database migration completed");
                promise.complete();
            } else if (ar.cause() instanceof StartupException) {
                // A schema this release cannot upgrade: retrying never helps.
                promise.fail(ar.cause());
            } else {
                logger.warn("Database migration failed ({}); retrying in {} ms", rootMessage(ar.cause()), backoffMs);
                vertx.setTimer(backoffMs, id -> attempt(vertx, executors, options, Math.min(backoffMs * 2, MAX_BACKOFF_MS), promise));
            }
        });
    }

    /**
     * The last retired migration version: 4.3.0 squashed the original V1 to V9 into {@code V9__baseline_4_0.sql}.
     */
    static final MigrationVersion LAST_RETIRED = MigrationVersion.fromVersion("9");

    /**
     * One migration attempt (4.3.0, squashed baseline). A new database runs the baseline. A database that ran the original
     * V1 to V9 keeps its history unchanged (so 4.2 still starts on it): only versions up to {@link #LAST_RETIRED} are
     * validated leniently, and V10 onwards are validated as usual. A schema older than 4.0.0 is refused.
     */
    static void migrateOnce(PgConnectOptions options) {
        Flyway plain = flyway(options);
        boolean retiredHistory = false;
        MigrationVersion maxApplied = null;
        for (MigrationInfo info : plain.info().applied()) {
            MigrationVersion v = info.getVersion();
            if (v == null || info.getType().isBaseline() || !info.getState().isApplied() || info.getState().isFailed()) {
                continue;
            }
            if (v.compareTo(LAST_RETIRED) < 0 && v.compareTo(MigrationVersion.fromVersion("1")) >= 0) {
                retiredHistory = true;
            }
            if (maxApplied == null || v.isNewerThan(maxApplied)) {
                maxApplied = v;
            }
        }
        if (!retiredHistory) {
            plain.migrate();
            return;
        }
        if (maxApplied.compareTo(LAST_RETIRED) < 0) {
            throw new StartupException("Database schema is from a release before 4.0.0 (migration " + maxApplied
                    + "); this release cannot upgrade it. Upgrade with 4.2.0 first (FV_TAG=4.2.0), then start this release"
                    + " (docs/upgrade-4.3.md)");
        }
        Flyway lenient = flywayBuilder(options)
                .ignoreMigrationPatterns("*:missing", "*:pending", "*:future")
                .validateOnMigrate(false)
                .load();
        ValidateResult result = lenient.validateWithResult();
        List<String> errors = result.invalidMigrations.stream()
                .filter(o -> o.version == null || o.version.isEmpty() || MigrationVersion.fromVersion(o.version).isNewerThan(LAST_RETIRED))
                .map(o -> o.errorDetails == null ? String.valueOf(o.version) : o.errorDetails.errorMessage)
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Migration validation failed: " + String.join("; ", errors));
        }
        logger.info("Database keeps the original V1-V{} history; retired versions validated leniently", LAST_RETIRED);
        lenient.migrate();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
