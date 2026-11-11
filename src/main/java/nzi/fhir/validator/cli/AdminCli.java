package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.config.ConfigLoader;
import nzi.fhir.validator.core.config.PgConfig;
import nzi.fhir.validator.core.org.Organisation;
import nzi.fhir.validator.core.org.OrganisationRepository;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The administrative command line (spec 005 FR-022 to FR-026, contracts/cli.md, research R9), in the service jar:
 * {@code java -jar app.jar <org|key|package|snapshot> <command> [options]}. It migrates the database, runs one
 * command against the same repositories as the service, prints one JSON document on stdout, and exits with 0 (ok),
 * 1 (unexpected failure), 2 (usage or validation), 3 (not found) or 4 (conflict). Errors are one line on stderr; logs
 * go to stderr too, so stdout is pure JSON. No HTTP server is started.
 * <p>
 * The calling thread waits on a latch, never on a future and never on an event loop (constitution Principle I).
 */
public final class AdminCli {
    /** Longest a command may take, migrations included. */
    static final long COMMAND_TIMEOUT_MS = 120_000;
    private static final int POOL_SIZE = 2;

    private AdminCli() {
    }

    /** Whether {@code args} name a command group, so {@code Bootstrap.main} runs the command line, not the server. */
    public static boolean isCommand(String[] args) {
        return args != null && args.length > 0 && CliArgs.GROUPS.contains(args[0]);
    }

    /**
     * Sends every log line, and Log4j's own status messages, to stderr (log4j2-cli.xml), so stdout carries only the JSON
     * result. Call before anything logs: the configuration is chosen when Log4j initialises; if it already has, it is
     * reconfigured.
     */
    public static void useStderrLogging() {
        System.setProperty("log4j2.configurationFile", "log4j2-cli.xml");
        System.setProperty("log4j2.statusLoggerLevel", "ERROR");
        URL config = AdminCli.class.getClassLoader().getResource("log4j2-cli.xml");
        if (config != null) {
            try {
                Configurator.reconfigure(config.toURI());
            } catch (URISyntaxException e) {
                // Keep the default configuration; stdout may then carry log lines.
            }
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        return run(args, out, err, env, System.in);
    }

    /**
     * Runs one command.
     *
     * @param env   the configuration environment ({@code FV_PG_*} and friends), as for the service
     * @param stdin read by {@code key create --key-stdin}
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env, InputStream stdin) {
        CliArgs.Command cmd;
        ApplicationConfig config;
        try {
            cmd = CliArgs.parse(args);
            config = ConfigLoader.load(env);
        } catch (CliException e) {
            return fail(err, e.exitCode(), e.code(), e.getMessage());
        } catch (IllegalStateException e) {
            return fail(err, CliException.USAGE, "configuration", e.getMessage());
        }

        Vertx vertx = Vertx.vertx();
        Executors executors = Executors.create(vertx, 1, config.validationTimeoutMs(), 1);
        Pool pool = PgBuilder.pool().connectingTo(PgConfig.createPgOptions(config))
                .with(new PoolOptions().setMaxSize(POOL_SIZE)).using(vertx).build();
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            DatabaseService.migrate(vertx, executors, PgConfig.createPgOptions(config))
                    .compose(v -> context(vertx, pool, stdin))
                    .compose(ctx -> execute(cmd, ctx))
                    .onComplete(ar -> {
                        if (ar.succeeded()) {
                            result.set(ar.result());
                        } else {
                            failure.set(ar.cause());
                        }
                        done.countDown();
                    });
            if (!done.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return fail(err, 1, "timeout", "The command did not finish within " + COMMAND_TIMEOUT_MS / 1000 + " s");
            }
        } catch (CliException e) {
            return fail(err, e.exitCode(), e.code(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(err, 1, "interrupted", "Interrupted");
        } finally {
            close(pool, executors, vertx);
        }
        if (failure.get() != null) {
            return report(err, failure.get());
        }
        Object value = result.get();
        out.println(value instanceof JsonArray a ? a.encodePrettily() : ((JsonObject) value).encodePrettily());
        out.flush();
        return 0;
    }

    private static Future<CliContext> context(Vertx vertx, Pool pool, InputStream stdin) {
        OrganisationRepository organisations = new OrganisationRepository(pool);
        return organisations.findById(Organisations.SYSTEM_ID)
                .map(system -> new CliContext(vertx, pool, organisations, new ApiKeyRepository(pool),
                        IgPackageService.create(vertx, pool),
                        Caller.system(system.map(Organisation::nameShort).orElse(Organisations.DEFAULT_SYSTEM_SHORT_NAME)),
                        stdin));
    }

    private static Future<Object> execute(CliArgs.Command cmd, CliContext ctx) {
        try {
            return switch (cmd.group()) {
                case "org" -> OrgCommands.run(cmd, ctx);
                case "key" -> KeyCommands.run(cmd, ctx);
                case "package", "snapshot" -> AccessCommands.run(cmd, ctx);
                case "usage" -> UsageCommands.run(cmd, ctx);
                default -> Future.failedFuture(CliException.usage("Unknown command group '" + cmd.group() + "'"));
            };
        } catch (CliException e) {
            return Future.failedFuture(e);
        }
    }

    /** Maps a failure to its exit code (contracts/cli.md) and prints one curated line. */
    private static int report(PrintStream err, Throwable t) {
        if (t instanceof CliException e) {
            return fail(err, e.exitCode(), e.code(), e.getMessage());
        }
        if (t instanceof ProblemException p) {
            int code = switch (p.status()) {
                case 400 -> CliException.USAGE;
                case 404 -> CliException.NOT_FOUND;
                case 403, 409 -> CliException.CONFLICT;
                default -> 1;
            };
            return fail(err, code, p.type(), p.detail());
        }
        // Internal causes go to the log (stderr), never into the curated error line.
        LogManager.getLogger(AdminCli.class).error("Command failed", t);
        return fail(err, 1, "internal", "The command failed (see the log above)");
    }

    private static int fail(PrintStream err, int exitCode, String code, String message) {
        err.println("error: " + code + ": " + message);
        err.flush();
        return exitCode;
    }

    private static void close(Pool pool, Executors executors, Vertx vertx) {
        try {
            CountDownLatch released = new CountDownLatch(1);
            pool.close().eventually(executors::close).onComplete(ar -> released.countDown());
            released.await(10, TimeUnit.SECONDS);
            // From this (non-Vert.x) thread: closing Vert.x from one of its own callbacks never completes.
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            closed.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
