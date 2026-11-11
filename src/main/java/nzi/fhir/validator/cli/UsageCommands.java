package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.org.OrganisationRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * {@code usage summary (--org <short name> | --key <name>) [--since <ISO-8601 instant>]} (spec 008 FR-022,
 * contracts/operations.md §2): stored usage of one organisation or one key since a time (default: the last 24 hours),
 * from the {@code db} usage sink's table. Unknown CPU and allocation values (-1) count as 0.
 */
final class UsageCommands {
    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".validation_usage";

    private UsageCommands() {
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        if (!"summary".equals(cmd.command())) {
            return Future.failedFuture(CliException.usage("Unknown command 'usage " + cmd.command() + "'"));
        }
        boolean byOrg = cmd.has("org");
        boolean byKey = cmd.has("key");
        if (byOrg == byKey) {
            throw CliException.usage("Give exactly one of --org or --key");
        }
        Instant since;
        try {
            since = cmd.value("since").map(s -> Instant.parse(s.strip())).orElse(Instant.now().minus(Duration.ofHours(24)));
        } catch (DateTimeParseException e) {
            throw CliException.usage("--since must be an ISO-8601 instant, e.g. 2026-11-01T00:00:00Z");
        }
        Future<UUID> scope = byOrg
                ? ctx.organisations().findByShortName(cmd.required("org")).compose(o -> o.isPresent()
                ? Future.succeededFuture(o.get().id()) : Future.failedFuture(OrganisationRepository.notFound()))
                : ctx.pool().preparedQuery("SELECT id FROM " + DB_POSTGRES_SCHEMA_NAME + ".api_keys WHERE name = $1")
                .execute(Tuple.of(cmd.required("key")))
                .compose(rows -> rows.size() == 1 ? Future.succeededFuture(rows.iterator().next().getUUID(0))
                        : Future.failedFuture(new nzi.fhir.validator.web.ProblemException(404, "not-found", "API key not found")));
        String column = byOrg ? "org_id" : "api_key_id";
        JsonObject result = new JsonObject()
                .put("scope", new JsonObject().put(byOrg ? "org" : "key", cmd.required(byOrg ? "org" : "key")))
                .put("since", since.toString());
        return scope.compose(id -> {
            Tuple params = Tuple.of(id, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
            String where = " FROM " + TABLE + " WHERE " + column + " = $1 AND created_at >= $2";
            return ctx.pool().preparedQuery("SELECT count(*), coalesce(sum(total_ms), 0), coalesce(avg(total_ms), 0), "
                            + "coalesce(sum(greatest(validate_ms, 0)), 0), coalesce(sum(greatest(cpu_time_ns, 0)), 0), "
                            + "coalesce(sum(greatest(allocated_bytes, 0)), 0)" + where)
                    .execute(params)
                    .compose(totals -> {
                        Row r = totals.iterator().next();
                        result.put("validations", r.getLong(0))
                                .put("totalMs", r.getLong(1))
                                .put("avgMs", Math.round(r.getNumeric(2).doubleValue() * 10) / 10.0)
                                .put("validateMs", r.getLong(3))
                                .put("cpuTimeNs", r.getLong(4))
                                .put("allocatedBytes", r.getLong(5));
                        return ctx.pool().preparedQuery("SELECT outcome, count(*)" + where + " GROUP BY outcome ORDER BY outcome")
                                .execute(params);
                    })
                    .map(byOutcome -> {
                        JsonObject outcomes = new JsonObject();
                        byOutcome.forEach(r -> outcomes.put(r.getString(0), r.getLong(1)));
                        return (Object) result.put("byOutcome", outcomes);
                    });
        });
    }
}
