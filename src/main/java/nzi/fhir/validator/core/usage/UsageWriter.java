package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.metrics.ServiceMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where usage records go (spec 008 FR-014): chosen by {@code FV_USAGE_SINK}. {@link #write} is called on the event
 * loop and must never block or wait (FR-017).
 */
public interface UsageWriter {

    void write(UsageRecord record);

    /** Flushes what is buffered and releases resources; called once, after the shutdown drain (FR-018). */
    Future<Void> close();

    /** Whether records are kept at all; {@code false} only for {@code none}. */
    default boolean enabled() {
        return true;
    }

    /** The writer for {@code config.usageSinks()}: {@code none}, {@code log}, {@code db}, or both. */
    static UsageWriter create(Vertx vertx, Pool pool, ApplicationConfig config, ServiceMetrics metrics) {
        Set<String> sinks = config.usageSinks();
        List<UsageWriter> writers = new ArrayList<>();
        if (sinks.contains("log")) {
            writers.add(new LogUsageWriter(metrics));
        }
        if (sinks.contains("db")) {
            writers.add(new DbUsageWriter(vertx, pool, metrics, config.usageQueueSize(), config.usageBatchSize(),
                    config.usageFlushMs()));
        }
        return switch (writers.size()) {
            case 0 -> NoopUsageWriter.INSTANCE;
            case 1 -> writers.get(0);
            default -> new CompositeUsageWriter(writers);
        };
    }
}
