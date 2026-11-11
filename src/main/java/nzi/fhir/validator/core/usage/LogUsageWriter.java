package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@code FV_USAGE_SINK=log} (spec 008 FR-015, FR-017): one JSON object per record at INFO on the dedicated logger
 * {@value #LOGGER}. {@code log4j2.xml} routes it to its own appender ({@code %msg%n}, no additivity), so each line is
 * exactly the JSON object and operators can send it to a file or another stream.
 * <p>
 * {@link #write} is called on the event loop, so it only offers the line to a bounded queue. The lines are written by
 * one long-running task on the named worker pool {@value #POOL} (size 1, constitution I). When the queue is full (a
 * slow stdout or log driver), the line is dropped and counted as {@code fv.usage.dropped{reason="log_full"}}.
 */
public final class LogUsageWriter implements UsageWriter {
    public static final String LOGGER = "nzi.fhir.validator.usage";
    /** The worker pool that writes the lines. */
    public static final String POOL = "fv-usage-log";
    /** Lines waiting to be written. */
    public static final int DEFAULT_CAPACITY = 8_192;
    private static final Logger logger = LogManager.getLogger(LOGGER);
    private static final long CLOSE_DEADLINE_MS = 5_000;
    /** The drain task runs as long as the writer: never report it as a blocked worker. */
    private static final long MAX_EXECUTE_DAYS = 3_650;

    private final Vertx vertx;
    private final ServiceMetrics metrics;
    private final BlockingQueue<String> queue;
    private final Consumer<String> out;
    private final WorkerExecutor executor;
    private final Future<Void> drained;
    private volatile boolean closed;

    public LogUsageWriter(Vertx vertx, ServiceMetrics metrics) {
        this(vertx, metrics, DEFAULT_CAPACITY, logger::info);
    }

    /** For tests: {@code out} receives each line on the worker. */
    LogUsageWriter(Vertx vertx, ServiceMetrics metrics, int capacity, Consumer<String> out) {
        this.vertx = vertx;
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.out = out;
        this.executor = vertx.createSharedWorkerExecutor(POOL, 1, MAX_EXECUTE_DAYS, TimeUnit.DAYS);
        this.drained = executor.executeBlocking(() -> {
            drain();
            return null;
        }, false);
    }

    @Override
    public void write(UsageRecord record) {
        if (closed) {
            dropped("closed", 1);
            return;
        }
        if (!queue.offer(record.toJson().encode())) {
            dropped("log_full", 1);
        }
    }

    /** Lines waiting (tests). */
    int queued() {
        return queue.size();
    }

    private void drain() throws InterruptedException {
        while (!closed || !queue.isEmpty()) {
            String line = queue.poll(100, TimeUnit.MILLISECONDS);
            if (line != null) {
                out.accept(line);
            }
        }
    }

    /**
     * Stops accepting lines and completes when the waiting ones are written, or after 5 s, when what is left is dropped
     * and counted. Never blocks the caller.
     */
    @Override
    public Future<Void> close() {
        closed = true;
        Promise<Void> done = Promise.promise();
        long timer = vertx.setTimer(CLOSE_DEADLINE_MS, id -> {
            int left = queue.size();
            queue.clear();
            dropped("closed", left);
            done.tryComplete();
        });
        drained.onComplete(ar -> {
            vertx.cancelTimer(timer);
            done.tryComplete();
        });
        return done.future().eventually(() -> executor.close());
    }

    private void dropped(String reason, long n) {
        if (metrics != null && n > 0) {
            metrics.usageDropped(reason, n);
        }
    }
}
