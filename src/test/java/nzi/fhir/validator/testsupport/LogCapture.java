package nzi.fhir.validator.testsupport;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures formatted log events (with their context data) for assertions. Close it when done.
 */
public final class LogCapture implements AutoCloseable {

    /** {@code requestId}: the correlation id rendered by the {@code %vcl{requestId}} converter, empty outside requests. */
    public record Captured(String logger, Level level, String message, java.util.Map<String, String> context, String requestId) {
    }

    private final List<Captured> events = new CopyOnWriteArrayList<>();
    private final AbstractAppender appender;
    private final LoggerContext context;

    private LogCapture() {
        context = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.layout.PatternLayout requestIdLayout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withConfiguration(context.getConfiguration()).withPattern("%vcl{requestId:-}").build();
        appender = new AbstractAppender("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                // Rendered synchronously, on the thread (and Vert.x context) that logged the event.
                events.add(new Captured(event.getLoggerName(), event.getLevel(), event.getMessage().getFormattedMessage(),
                        event.getContextData().toMap(), requestIdLayout.toSerializable(event)));
            }
        };
        appender.start();
        Configuration config = context.getConfiguration();
        config.getRootLogger().addAppender(appender, Level.ALL, null);
        config.getLoggers().values().forEach(l -> l.addAppender(appender, Level.ALL, null));
        context.updateLoggers();
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    public List<Captured> events() {
        return List.copyOf(events);
    }

    public List<Captured> from(String loggerName) {
        return events.stream().filter(e -> e.logger().equals(loggerName)).toList();
    }

    @Override
    public void close() {
        Configuration config = context.getConfiguration();
        config.getRootLogger().removeAppender(appender.getName());
        config.getLoggers().values().forEach(l -> l.removeAppender(appender.getName()));
        context.updateLoggers();
        appender.stop();
    }
}
