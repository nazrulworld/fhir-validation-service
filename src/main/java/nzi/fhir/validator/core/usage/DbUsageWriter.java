package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * {@code FV_USAGE_SINK=db} (spec 008 FR-016, FR-017, research R12): records wait in a bounded queue and are written in
 * batches, when {@code batchSize} records are waiting or every {@code flushMs}, by one pipelined batch insert. The
 * request path only offers to the queue: when it is full the record is dropped and counted. A failed write is logged
 * (at most once a minute), counted and dropped; it is never retried.
 */
public final class DbUsageWriter implements UsageWriter {
    private static final Logger logger = LogManager.getLogger(DbUsageWriter.class);
    private static final long WARN_INTERVAL_MS = 60_000;
    private static final long CLOSE_DEADLINE_MS = 5_000;

    static final String INSERT = "INSERT INTO " + DB_POSTGRES_SCHEMA_NAME + ".validation_usage (started_at, request_id, "
            + "api_key_id, api_key_name, org_id, fhir_version, ig, profile_count, content_type, request_bytes, outcome, "
            + "http_status, issues_fatal, issues_error, issues_warning, issues_information, client_ip, total_ms, queue_ms, "
            + "context_ms, validate_ms, cpu_time_ns, allocated_bytes) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, "
            + "$12, $13, $14, $15, $16, $17::text::inet, $18, $19, $20, $21, $22, $23)";

    private final Vertx vertx;
    private final Pool pool;
    private final ServiceMetrics metrics;
    private final int batchSize;
    private final ArrayBlockingQueue<UsageRecord> queue;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastWarn = new AtomicLong();
    private final long timer;

    public DbUsageWriter(Vertx vertx, Pool pool, ServiceMetrics metrics, int queueSize, int batchSize, long flushMs) {
        this.vertx = vertx;
        this.pool = pool;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        if (metrics != null) {
            metrics.bindUsageQueue(queue);
        }
        this.timer = vertx.setPeriodic(flushMs, id -> flush());
    }

    /**
     * Offers the record; never waits. A dropped record is counted by why: {@code unattributed} (no organisation),
     * {@code closed} (after shutdown began) or {@code queue_full}.
     */
    @Override
    public void write(UsageRecord record) {
        if (record.orgId() == null) {
            dropped("unattributed", 1);
            return;
        }
        if (closed.get()) {
            dropped("closed", 1);
            return;
        }
        if (!queue.offer(record)) {
            dropped("queue_full", 1);
            return;
        }
        // A running flush reschedules itself while a full batch waits, so only an idle writer needs a nudge.
        if (queue.size() >= batchSize && !flushing.get()) {
            vertx.runOnContext(v -> flush());
        }
    }

    /** Records waiting to be written (tests). */
    int queued() {
        return queue.size();
    }

    /** Writes one batch; another flush follows at once while a full batch is waiting. Never two at a time. */
    Future<Void> flush() {
        if (queue.isEmpty() || !flushing.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        List<UsageRecord> batch = new ArrayList<>(Math.min(batchSize, queue.size()));
        queue.drainTo(batch, batchSize);
        List<Tuple> tuples = batch.stream().map(UsageRecord::toTuple).toList();
        return pool.preparedQuery(INSERT).executeBatch(tuples)
                .<Void>mapEmpty()
                .onSuccess(v -> {
                    if (metrics != null) {
                        metrics.usageWritten(batch.size());
                    }
                })
                .recover(err -> {
                    dropped("write_failed", batch.size());
                    long now = System.currentTimeMillis();
                    long last = lastWarn.get();
                    if (now - last >= WARN_INTERVAL_MS && lastWarn.compareAndSet(last, now)) {
                        logger.warn("Usage records could not be written ({} dropped, not retried): {}", batch.size(),
                                err.getMessage());
                    }
                    return Future.succeededFuture();
                })
                .eventually(() -> {
                    flushing.set(false);
                    if (queue.size() >= batchSize && !closed.get()) {
                        vertx.runOnContext(v -> flush());
                    }
                    return Future.succeededFuture();
                });
    }

    @Override
    public Future<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        vertx.cancelTimer(timer);
        Promise<Void> done = Promise.promise();
        drain(System.currentTimeMillis() + CLOSE_DEADLINE_MS, done);
        return done.future();
    }

    private void drain(long deadline, Promise<Void> done) {
        if (queue.isEmpty() || System.currentTimeMillis() >= deadline) {
            if (!queue.isEmpty()) {
                int left = queue.size();
                queue.clear();
                dropped("write_failed", left);
                logger.warn("{} usage record(s) not written before shutdown", left);
            }
            done.complete();
            return;
        }
        if (flushing.get()) {
            vertx.setTimer(10, id -> drain(deadline, done));
            return;
        }
        flush().onComplete(ar -> drain(deadline, done));
    }

    private void dropped(String reason, long n) {
        if (metrics != null) {
            metrics.usageDropped(reason, n);
        }
    }
}
