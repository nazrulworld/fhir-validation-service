package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import io.vertx.core.Promise;
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
 * {@link #write} is called on the event loop, so it only offers the line to a bounded queue; one daemon thread writes
 * the lines. When the queue is full (a slow stdout or log driver), the line is dropped and counted as
 * {@code fv.usage.dropped{reason="log_full"}}.
 */
public final class LogUsageWriter implements UsageWriter {
    public static final String LOGGER = "nzi.fhir.validator.usage";
    /** Lines waiting to be written. */
    public static final int DEFAULT_CAPACITY = 8_192;
    private static final Logger logger = LogManager.getLogger(LOGGER);
    private static final long CLOSE_DEADLINE_MS = 5_000;

    private final ServiceMetrics metrics;
    private final BlockingQueue<String> queue;
    private final Consumer<String> out;
    private final Thread drainer;
    private volatile boolean closed;

    public LogUsageWriter(ServiceMetrics metrics) {
        this(metrics, DEFAULT_CAPACITY, logger::info);
    }

    /** For tests: {@code out} receives each line on the writer thread. */
    LogUsageWriter(ServiceMetrics metrics, int capacity, Consumer<String> out) {
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.out = out;
        this.drainer = new Thread(this::drain, "fv-usage-log");
        this.drainer.setDaemon(true);
        this.drainer.start();
    }

    @Override
    public void write(UsageRecord record) {
        if (closed) {
            dropped("closed");
            return;
        }
        if (!queue.offer(record.toJson().encode())) {
            dropped("log_full");
        }
    }

    /** Lines waiting (tests). */
    int queued() {
        return queue.size();
    }

    private void drain() {
        try {
            while (!closed || !queue.isEmpty()) {
                String line = queue.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    out.accept(line);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes what is waiting (up to 5 s), then stops the writer thread. Never blocks the calling event loop. */
    @Override
    public Future<Void> close() {
        closed = true;
        Promise<Void> done = Promise.promise();
        Thread waiter = new Thread(() -> {
            try {
                drainer.join(CLOSE_DEADLINE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (drainer.isAlive()) {
                drainer.interrupt();
                int left = queue.size();
                queue.clear();
                for (int i = 0; i < left; i++) {
                    dropped("closed");
                }
            }
            done.complete();
        }, "fv-usage-log-close");
        waiter.setDaemon(true);
        waiter.start();
        return done.future();
    }

    private void dropped(String reason) {
        if (metrics != null) {
            metrics.usageDropped(reason, 1);
        }
    }
}
