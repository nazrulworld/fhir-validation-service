package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Reads of {@code validation_usage} (spec 008 FR-022). Writes are {@link DbUsageWriter}'s.
 */
public class UsageRepository {
    static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".validation_usage";

    /** What a summary is scoped to. */
    public enum Scope {
        ORG("org_id"), KEY("api_key_id");

        private final String column;

        Scope(String column) {
            this.column = column;
        }
    }

    private final Pool pool;

    public UsageRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * Totals of the validations of one organisation or key that <em>started</em> at or after {@code since}. Rows are
     * inserted after they start ({@code created_at >= started_at}), so {@code created_at >= since} is a safe extra bound
     * that keeps the {@code (…, created_at)} indexes usable. Unknown CPU and allocation values (-1) count as 0.
     *
     * @return {@code validations, totalMs, avgMs, validateMs, cpuTimeNs, allocatedBytes, byOutcome{outcome: count}}
     */
    public Future<JsonObject> summary(Scope scope, UUID id, Instant since) {
        Tuple params = Tuple.of(id, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        String where = " FROM " + TABLE + " WHERE " + scope.column + " = $1 AND created_at >= $2 AND started_at >= $2";
        JsonObject result = new JsonObject();
        return pool.preparedQuery("SELECT count(*), coalesce(sum(total_ms), 0), coalesce(avg(total_ms), 0), "
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
                    return pool.preparedQuery("SELECT outcome, count(*)" + where + " GROUP BY outcome ORDER BY outcome")
                            .execute(params);
                })
                .map(byOutcome -> {
                    JsonObject outcomes = new JsonObject();
                    byOutcome.forEach(r -> outcomes.put(r.getString(0), r.getLong(1)));
                    return result.put("byOutcome", outcomes);
                });
    }
}
