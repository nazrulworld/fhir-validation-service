package nzi.fhir.validator.core.usage;

import io.micrometer.core.instrument.Counter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 FR-016 to FR-019 (research R12): batching, flushing, drop-and-count, no retries, flush on close. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class DbUsageWriterTest extends BaseTestContainer {
    private static final String TABLE = "fhir_validator_schema.validation_usage";

    private Pool pool;
    private ServiceMetrics metrics;
    private DbUsageWriter writer;

    @BeforeEach
    void setUp(Vertx vertx) {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        pool = createPgPool(vertx);
        metrics = new ServiceMetrics(ServiceMetrics.newRegistry());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (writer == null ? Future.<Void>succeededFuture() : writer.close())
                .eventually(() -> pool.close()).onComplete(ar -> ctx.completeNow());
    }

    private static UsageRecord record(UUID keyId, int i) {
        return new UsageRecord("req-" + i, Instant.now(), keyId, keyId == null ? null : "k", Constant.SYSTEM_ORG_UUID, "R4",
                "base", 0, "JSON", 10, "valid", 200, 0, 0, 0, 0, "127.0.0.1", 5, 0, 0, 4, 1_000, 2_000);
    }

    private Future<Integer> rows() {
        return pool.query("SELECT count(*) FROM " + TABLE).execute().map(rs -> rs.iterator().next().getInteger(0));
    }

    private double counter(String name, String... tags) {
        Counter c = metrics.registry().find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void flushesWhenBatchSizeIsReached(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 10, 60_000);
        for (int i = 0; i < 10; i++) {
            writer.write(record(null, i));
        }
        Await.until(vertx, 5_000, "10 rows written without waiting for the timer", () -> rows().map(n -> n == 10))
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertEquals(10, counter("fv.usage.written"));
                    ctx.completeNow();
                })));
    }

    @Test
    void flushesOnTheTimer(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 200);
        for (int i = 0; i < 3; i++) {
            writer.write(record(null, i));
        }
        Await.until(vertx, 5_000, "3 rows written by the timer", () -> rows().map(n -> n == 3))
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void dropsAndCountsWhenTheQueueIsFullAndNeverBlocks(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 100, 500, 60_000);
        long start = System.nanoTime();
        for (int i = 0; i < 150; i++) {
            writer.write(record(null, i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        ctx.verify(() -> {
            assertTrue(elapsedMs < 50, "150 writes took " + elapsedMs + " ms");
            assertEquals(100, writer.queued());
            assertEquals(50, counter("fv.usage.dropped", "reason", "queue_full"));
            assertNotNull(metrics.registry().find("fv.usage.queue.size").gauge());
        });
        ctx.completeNow();
    }

    @Test
    void aFailedWriteIsCountedAndNotRetried(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        pool.query("ALTER TABLE " + TABLE + " RENAME TO validation_usage_off").execute()
                .compose(v -> {
                    for (int i = 0; i < 5; i++) {
                        writer.write(record(null, i));
                    }
                    return writer.flush();
                })
                .compose(v -> {
                    ctx.verify(() -> {
                        assertEquals(5, counter("fv.usage.dropped", "reason", "write_failed"));
                        assertEquals(0, writer.queued(), "the failed batch is not kept for a retry");
                    });
                    return pool.query("ALTER TABLE fhir_validator_schema.validation_usage_off RENAME TO validation_usage").execute();
                })
                .compose(v -> {
                    writer.write(record(null, 99));
                    return writer.flush();
                })
                .compose(v -> rows())
                .onComplete(ctx.succeeding(n -> ctx.verify(() -> {
                    assertEquals(1, n, "only the new record; the failed ones were dropped");
                    ctx.completeNow();
                })));
    }

    @Test
    void closeFlushesWhatIsBuffered(Vertx vertx, VertxTestContext ctx) {
        DbUsageWriter w = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        for (int i = 0; i < 7; i++) {
            w.write(record(null, i));
        }
        w.close()
                .compose(v -> rows())
                .onComplete(ctx.succeeding(n -> ctx.verify(() -> {
                    assertEquals(7, n);
                    w.write(record(null, 8));
                    assertEquals(0, w.queued(), "a closed writer keeps nothing");
                    // Review regression: counted by why it was lost, not as queue_full.
                    assertEquals(1, counter("fv.usage.dropped", "reason", "closed"));
                    assertEquals(0, counter("fv.usage.dropped", "reason", "queue_full"));
                    ctx.completeNow();
                })));
    }

    /** Code review regression (FR-018): close() waits for an insert already in flight before the pool may close. */
    @Test
    void closeWaitsForAnInFlightFlush(Vertx vertx, VertxTestContext ctx) {
        DbUsageWriter w = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        for (int i = 0; i < 50; i++) {
            w.write(record(null, i));
        }
        w.flush(); // in flight: the queue is now empty
        w.close()
                .compose(v -> rows())
                .onComplete(ctx.succeeding(n -> ctx.verify(() -> {
                    assertEquals(50, n, "every record of the in-flight batch is written when close() completes");
                    ctx.completeNow();
                })));
    }

    /** Review regression: a record without an organisation is dropped as "unattributed", not "queue_full". */
    @Test
    void unattributedRecordsAreCountedAsSuch(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        writer.write(new UsageRecord("r", Instant.now(), null, null, null, "R4", null, 0, null, 0, "valid", 200,
                0, 0, 0, 0, null, 1, 0, 0, 0, 0, 0));
        ctx.verify(() -> {
            assertEquals(0, writer.queued());
            assertEquals(1, counter("fv.usage.dropped", "reason", "unattributed"));
            assertEquals(0, counter("fv.usage.dropped", "reason", "queue_full"));
        });
        ctx.completeNow();
    }

    /** Review regression: client-controlled values longer than their columns never fail a batch. */
    @Test
    void oversizedValuesAreCutAndNeverLoseTheBatch(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        writer.write(record(null, 1));
        writer.write(new UsageRecord("r".repeat(100), Instant.now(), null, null, Constant.SYSTEM_ORG_UUID,
                "R4" + "x".repeat(40), "a".repeat(256), 0, "JSON", 10, "rejected", 400, 0, 0, 0, 0, null, 1, -1, -1, -1, -1, -1));
        writer.write(record(null, 3));
        writer.flush()
                .compose(v -> pool.query("SELECT length(ig), length(fhir_version), length(request_id) FROM " + TABLE
                        + " ORDER BY id").execute())
                .onComplete(ctx.succeeding(rs -> ctx.verify(() -> {
                    assertEquals(3, rs.size(), "the whole batch was written");
                    assertEquals(0, counter("fv.usage.dropped", "reason", "write_failed"));
                    io.vertx.sqlclient.Row long_ = java.util.stream.StreamSupport.stream(rs.spliterator(), false).toList().get(1);
                    assertEquals(192, long_.getInteger(0));
                    assertEquals(16, long_.getInteger(1));
                    assertEquals(64, long_.getInteger(2));
                    ctx.completeNow();
                })));
    }

    /** Code review regression: a record whose key was deleted meanwhile is stored with no key and never fails the batch. */
    @Test
    void aKeyDeletedWhileQueuedNeverFailsTheBatch(Vertx vertx, VertxTestContext ctx) {
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        writer.write(record(null, 1));
        writer.write(record(UUID.randomUUID(), 2)); // no such key any more
        writer.write(record(null, 3));
        writer.flush()
                .compose(v -> pool.query("SELECT count(*), count(api_key_id) FROM " + TABLE).execute())
                .onComplete(ctx.succeeding(rs -> ctx.verify(() -> {
                    io.vertx.sqlclient.Row r = rs.iterator().next();
                    assertEquals(3, r.getInteger(0), "the whole batch was written");
                    assertEquals(0, r.getInteger(1), "the deleted key is stored as NULL");
                    assertEquals(0, counter("fv.usage.dropped", "reason", "write_failed"));
                    ctx.completeNow();
                })));
    }

    @Test
    void deletingTheKeyKeepsItsRows(Vertx vertx, VertxTestContext ctx) {
        UUID keyId = UUID.randomUUID();
        writer = new DbUsageWriter(vertx, pool, metrics, 1_000, 500, 60_000);
        pool.preparedQuery("INSERT INTO fhir_validator_schema.api_keys (id, name, key_hash, role) VALUES ($1, 'gone', '\\x01', 'VALIDATE')")
                .execute(Tuple.of(keyId))
                .compose(v -> {
                    writer.write(record(keyId, 1));
                    return writer.flush();
                })
                .compose(v -> pool.preparedQuery("DELETE FROM fhir_validator_schema.api_keys WHERE id = $1").execute(Tuple.of(keyId)))
                .compose(v -> pool.query("SELECT coalesce(api_key_id::text, 'null') || '|' || api_key_name || '|' || org_id FROM "
                        + TABLE).execute())
                .onComplete(ctx.succeeding(rs -> ctx.verify(() -> {
                    assertEquals("null|k|" + Constant.SYSTEM_ORG_ID, rs.iterator().next().getString(0));
                    ctx.completeNow();
                })));
    }
}
