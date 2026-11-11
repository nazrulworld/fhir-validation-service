package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;

/** {@code FV_USAGE_SINK=none} (the default): keeps nothing. */
public final class NoopUsageWriter implements UsageWriter {
    public static final NoopUsageWriter INSTANCE = new NoopUsageWriter();

    private NoopUsageWriter() {
    }

    @Override
    public void write(UsageRecord record) {
    }

    @Override
    public Future<Void> close() {
        return Future.succeededFuture();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
