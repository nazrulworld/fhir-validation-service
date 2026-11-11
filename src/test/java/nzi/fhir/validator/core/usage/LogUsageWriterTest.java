package nzi.fhir.validator.core.usage;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.testsupport.LogCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 FR-015, contracts/operations.md §4: one JSON object per record on the dedicated usage logger. */
class LogUsageWriterTest {

    static UsageRecord sample() {
        return new UsageRecord("6f1c", Instant.parse("2026-11-11T10:15:02.113Z"), UUID.randomUUID(), "trial validator",
                UUID.randomUUID(), "R4", "hl7.fhir.us.core#6.1.0", 1, "JSON", 2312, "invalid", 200, 0, 2, 5, 1,
                "203.0.113.7", 84, 1, 0, 79, 71_234_000, 18_874_368);
    }

    @Test
    void oneJsonLineWithTheDocumentedKeys() {
        try (LogCapture logs = LogCapture.start()) {
            LogUsageWriter w = new LogUsageWriter(null);
            w.write(sample());
            w.close().toCompletionStage().toCompletableFuture().join();
            List<LogCapture.Captured> lines = logs.from(LogUsageWriter.LOGGER).stream().map(e -> e).distinct().toList();
            List<String> messages = lines.stream().map(LogCapture.Captured::message).distinct().toList();
            assertEquals(1, messages.size(), messages.toString());
            assertTrue(lines.stream().allMatch(e -> e.level() == Level.INFO));
            JsonObject json = new JsonObject(messages.get(0));
            assertEquals(List.of("requestId", "startedAt", "apiKeyId", "apiKeyName", "orgId", "fhirVersion", "ig", "profiles",
                    "contentType", "requestBytes", "outcome", "httpStatus", "issues", "clientIp", "totalMs", "queueMs",
                    "contextMs", "validateMs", "cpuTimeNs", "allocatedBytes"), List.copyOf(json.fieldNames()));
            assertEquals(List.of("fatal", "error", "warning", "information"),
                    List.copyOf(json.getJsonObject("issues").fieldNames()));
            assertEquals("2026-11-11T10:15:02.113Z", json.getString("startedAt"));
            assertEquals(71_234_000L, json.getLong("cpuTimeNs"));
        }
    }

    @Test
    void theUsageLoggerHasItsOwnPlainAppender() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig config = ctx.getConfiguration().getLoggerConfig(LogUsageWriter.LOGGER);
        assertEquals(LogUsageWriter.LOGGER, config.getName(), "declared in log4j2.xml");
        assertFalse(config.isAdditive(), "never duplicated into the application log");
        assertTrue(config.getAppenders().containsKey("UsageConsole"), config.getAppenders().toString());
        org.apache.logging.log4j.core.Layout<?> layout = ctx.getConfiguration().getAppender("UsageConsole").getLayout();
        String rendered = new String(layout.toByteArray(org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName(LogUsageWriter.LOGGER).setLevel(Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage(sample().toJson().encode())).build()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rendered.endsWith("\n"));
        assertEquals(sample().toJson().getString("ig"), new JsonObject(rendered.strip()).getString("ig"),
                "the rendered line is exactly the JSON object");
    }

    /**
     * Convergence T075 (FR-017): {@code write} never blocks; when the writer can't keep up, lines are dropped and
     * counted as {@code fv.usage.dropped{reason="log_full"}}.
     */
    @Test
    void aFullQueueDropsAndCountsWithoutBlocking() throws Exception {
        nzi.fhir.validator.core.metrics.ServiceMetrics metrics =
                new nzi.fhir.validator.core.metrics.ServiceMetrics(nzi.fhir.validator.core.metrics.ServiceMetrics.newRegistry());
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        LogUsageWriter w = new LogUsageWriter(metrics, 10, line -> {
            try {
                release.await(); // a stuck stdout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.add(line);
        });
        long start = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            w.write(sample());
        }
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 100, "30 writes took " + ms + " ms");
        // One line is held by the stuck writer thread, 10 wait in the queue, the rest are dropped.
        double dropped = metrics.registry().find("fv.usage.dropped").tags("reason", "log_full").counter().count();
        assertTrue(dropped >= 19 && dropped <= 20, "dropped " + dropped);
        release.countDown();
        w.close().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(30 - (int) dropped, written.size(), "everything not dropped is written by close()");
    }
}
