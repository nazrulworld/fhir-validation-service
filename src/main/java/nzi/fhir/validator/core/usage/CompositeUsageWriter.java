package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;

import java.util.List;

/** {@code FV_USAGE_SINK=log,db}: every record goes to each writer. */
public final class CompositeUsageWriter implements UsageWriter {
    private final List<UsageWriter> writers;

    public CompositeUsageWriter(List<UsageWriter> writers) {
        this.writers = List.copyOf(writers);
    }

    @Override
    public void write(UsageRecord record) {
        for (UsageWriter w : writers) {
            w.write(record);
        }
    }

    @Override
    public Future<Void> close() {
        return Future.join(writers.stream().map(UsageWriter::close).toList()).mapEmpty();
    }
}
