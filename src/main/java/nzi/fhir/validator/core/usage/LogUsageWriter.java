package nzi.fhir.validator.core.usage;

import io.vertx.core.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@code FV_USAGE_SINK=log} (spec 008 FR-015): one JSON object per record at INFO on the dedicated logger
 * {@value #LOGGER}. {@code log4j2.xml} routes it to its own appender ({@code %msg%n}, no additivity), so each line is
 * exactly the JSON object and operators can send it to a file or another stream.
 */
public final class LogUsageWriter implements UsageWriter {
    public static final String LOGGER = "nzi.fhir.validator.usage";
    private static final Logger logger = LogManager.getLogger(LOGGER);

    @Override
    public void write(UsageRecord record) {
        logger.info(record.toJson().encode());
    }

    @Override
    public Future<Void> close() {
        return Future.succeededFuture();
    }
}
