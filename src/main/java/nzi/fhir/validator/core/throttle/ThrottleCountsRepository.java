package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * The shared request counts in PostgreSQL (spec 014, research R4, data-model §2): the UNLOGGED tables
 * {@code throttle_counts} and {@code throttle_instances}. One statement per call; the request path never calls this.
 */
public final class ThrottleCountsRepository implements CountStore {
    private static final String COUNTS = DB_POSTGRES_SCHEMA_NAME + ".throttle_counts";
    private static final String INSTANCES = DB_POSTGRES_SCHEMA_NAME + ".throttle_instances";

    static final String FLUSH = "INSERT INTO " + COUNTS + " (scope, subject_id, epoch_second, instance_id, count) "
            + "SELECT s, id, sec, $5::uuid, n FROM unnest($1::text[], $2::uuid[], $3::int8[], $4::int4[]) AS u(s, id, sec, n) "
            + "ON CONFLICT (scope, subject_id, epoch_second, instance_id) DO UPDATE SET count = EXCLUDED.count";
    static final String HEARTBEAT = "WITH beat AS (INSERT INTO " + INSTANCES + " (instance_id, instance_name, seen_at) "
            + "VALUES ($1, $2, now()) ON CONFLICT (instance_id) DO UPDATE SET seen_at = now(), "
            + "instance_name = EXCLUDED.instance_name) "
            + "SELECT count(*) FROM " + INSTANCES + " WHERE instance_id <> $1 AND seen_at > now() - interval '5 seconds'";
    static final String READ = "SELECT scope, subject_id, epoch_second, sum(count)::int FROM " + COUNTS
            + " WHERE epoch_second >= $1 AND instance_id <> $2 GROUP BY 1, 2, 3";

    private final Pool pool;

    public ThrottleCountsRepository(Pool pool) {
        this.pool = pool;
    }

    @Override
    public Future<Void> flush(UUID instance, List<CountRow> rows) {
        if (rows.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] scopes = new String[rows.size()];
        UUID[] ids = new UUID[rows.size()];
        Long[] seconds = new Long[rows.size()];
        Integer[] counts = new Integer[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            CountRow r = rows.get(i);
            scopes[i] = String.valueOf(r.scope());
            ids[i] = r.subject();
            seconds[i] = r.second();
            counts[i] = r.count();
        }
        return pool.preparedQuery(FLUSH).execute(Tuple.of(scopes, ids, seconds, counts, instance)).mapEmpty();
    }

    @Override
    public Future<Integer> heartbeat(UUID instance, String name) {
        return pool.preparedQuery(HEARTBEAT).execute(Tuple.of(instance, name))
                .map(rows -> (int) rows.iterator().next().getLong(0).longValue());
    }

    @Override
    public Future<List<CountRow>> read(UUID instance, long sinceSecond) {
        return pool.preparedQuery(READ).execute(Tuple.of(sinceSecond, instance)).map(rows -> {
            List<CountRow> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                out.add(new CountRow(r.getString(0).charAt(0), r.getUUID(1), r.getLong(2), r.getInteger(3)));
            }
            return out;
        });
    }

    @Override
    public Future<Void> cleanup(long beforeSecond) {
        return pool.preparedQuery("DELETE FROM " + COUNTS + " WHERE epoch_second < $1").execute(Tuple.of(beforeSecond))
                .compose(v -> pool.query("DELETE FROM " + INSTANCES + " WHERE seen_at < now() - interval '1 hour'").execute())
                .mapEmpty();
    }

    @Override
    public Future<Void> remove(UUID instance) {
        return pool.preparedQuery("DELETE FROM " + INSTANCES + " WHERE instance_id = $1").execute(Tuple.of(instance)).mapEmpty();
    }
}
