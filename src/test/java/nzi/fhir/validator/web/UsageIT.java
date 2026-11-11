package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.usage.LogUsageWriter;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.LogCapture;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 008 US6 (FR-011 to FR-019, SC-006): one usage record per authorised validation request, attributed to the key
 * and its organisation, in the configured sink.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class UsageIT extends BaseTestContainer {
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";
    private static final String COLUMNS = "api_key_id::text, api_key_name, org_id::text, outcome, http_status, total_ms, "
            + "validate_ms, cpu_time_ns, allocated_bytes, host(client_ip), request_id, fhir_version, ig, content_type, "
            + "request_bytes, issues_error + issues_fatal + issues_warning + issues_information";

    private AppHarness app;
    private LogCapture logs;

    private Future<AppHarness> start(Vertx vertx, String sink, Integer requestTimeoutMs) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("usage", new JsonObject().put("sink", sink).put("flushMs", 100));
        if (requestTimeoutMs != null) {
            config.getJsonObject("http").put("requestTimeoutMs", requestTimeoutMs);
        }
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (logs != null) {
            logs.close();
        }
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private HttpRequest<Buffer> validateRequest(String auth) {
        HttpRequest<Buffer> r = app.client().post(app.port(), "127.0.0.1", "/R4/validate").timeout(60_000)
                .putHeader("Content-Type", "application/fhir+json");
        return auth == null ? r : r.putHeader("Authorization", auth);
    }

    private Future<List<Row>> usageRows() {
        return app.bootstrap().services().pool().query("SELECT " + COLUMNS + " FROM fhir_validator_schema.validation_usage ORDER BY id")
                .execute().map(rs -> {
                    List<Row> out = new ArrayList<>();
                    rs.forEach(out::add);
                    return out;
                });
    }

    private Future<List<Row>> awaitRows(Vertx vertx, int n) {
        return Await.until(vertx, 10_000, n + " usage row(s)", () -> usageRows().map(r -> r.size() >= n))
                .compose(v -> usageRows());
    }

    @Test
    void dbSinkRecordsOneRowPerValidationAttributedToTheKey(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "db", null)
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer(PATIENT)))
                .compose(ok -> {
                    ctx.verify(() -> assertEquals(200, ok.statusCode(), ok.bodyAsString()));
                    return awaitRows(vertx, 1).map(rows -> {
                        ctx.verify(() -> {
                            assertEquals(1, rows.size());
                            Row r = rows.get(0);
                            assertEquals(Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID, r.getString(0));
                            assertEquals("trial validator", r.getString(1));
                            assertEquals(Constant.TRIAL_ORG_ID, r.getString(2));
                            assertTrue(List.of("valid", "invalid").contains(r.getString(3)), r.getString(3));
                            assertEquals(200, r.getInteger(4));
                            assertTrue(r.getInteger(5) >= r.getInteger(6) && r.getInteger(6) >= 0, "total >= validate >= 0");
                            assertTrue(r.getLong(7) > 0, "cpu_time_ns > 0");
                            assertTrue(r.getLong(8) > 0, "allocated_bytes > 0");
                            assertEquals("127.0.0.1", r.getString(9));
                            assertEquals(ok.getHeader(RequestIdHandler.HEADER), r.getString(10));
                            assertEquals("R4", r.getString(11));
                            assertEquals("base", r.getString(12));
                            assertEquals("JSON", r.getString(13));
                            assertEquals(PATIENT.length(), r.getInteger(14));
                        });
                        return rows;
                    });
                })
                .compose(v -> app.client().get(app.port(), "localhost", "/metrics").send())
                .onComplete(ctx.succeeding(m -> ctx.verify(() -> {
                    String body = m.bodyAsString();
                    assertTrue(body.contains("fv_usage_written_total"), "written counter");
                    assertTrue(body.contains("fv_usage_queue_size"), "queue gauge");
                    ctx.completeNow();
                })));
    }

    @Test
    void errorsRejectionsAndRefusalsAreRecordedAsTheSpecSays(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "db", null)
                .compose(a -> Future.all(
                        validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer("{not fhir")),
                        // Refused by the endpoint before validating: a malformed ig reference.
                        validateRequest(AppHarness.VALIDATOR_AUTH).addQueryParam("ig", "some.pkg#1.2.x").sendBuffer(Buffer.buffer(PATIENT)),
                        validateRequest(null).sendBuffer(Buffer.buffer(PATIENT))))
                .compose(responses -> {
                    ctx.verify(() -> {
                        assertEquals(400, ((HttpResponse<?>) responses.resultAt(0)).statusCode());
                        assertEquals(400, ((HttpResponse<?>) responses.resultAt(1)).statusCode());
                        assertEquals(401, ((HttpResponse<?>) responses.resultAt(2)).statusCode());
                    });
                    return awaitRows(vertx, 2);
                })
                .compose(rows -> vertx.timer(500).compose(t -> usageRows()))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(2, rows.size(), "no row for the 401: there is no key to attribute; got "
                            + rows.stream().map(r -> r.getString(3) + "|" + r.getInteger(4)).toList());
                    List<String> outcomes = rows.stream().map(r -> r.getString(3) + "|" + r.getInteger(4)).sorted().toList();
                    assertEquals(List.of("rejected|400", "unparseable|400"), outcomes);
                    Row rejected = rows.stream().filter(r -> r.getString(3).equals("rejected")).findFirst().orElseThrow();
                    assertEquals(-1, rejected.getInteger(6), "validate_ms is -1 when the work never started");
                    ctx.completeNow();
                })));
    }

    /**
     * Review regression: a well-formed but 250-character {@code ig} and a bogus version path segment are longer than
     * their columns; they are cut, and the other requests' rows in the same batch are still written.
     */
    @Test
    void oversizedClientValuesNeverLoseOtherRows(Vertx vertx, VertxTestContext ctx) {
        String longIg = "a" + "b".repeat(240) + "#1.0.0";
        start(vertx, "db", null)
                .compose(a -> Future.all(
                        validateRequest(AppHarness.VALIDATOR_AUTH).addQueryParam("ig", longIg).sendBuffer(Buffer.buffer(PATIENT)),
                        app.client().post(app.port(), "127.0.0.1", "/R4xxxxxxxxxxxxxxxxxxxxxx/validate")
                                .putHeader("Authorization", AppHarness.VALIDATOR_AUTH)
                                .putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(PATIENT)),
                        validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer(PATIENT))))
                .compose(responses -> awaitRows(vertx, 3))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(3, rows.size());
                    assertTrue(rows.stream().anyMatch(r -> r.getString(12) != null && r.getString(12).length() == 192), "ig cut to 192");
                    assertTrue(rows.stream().anyMatch(r -> r.getString(11) != null && r.getString(11).length() == 16), "version cut to 16");
                    assertTrue(rows.stream().anyMatch(r -> r.getInteger(4) == 200), "the ordinary validation is there too");
                    ctx.completeNow();
                })));
    }

    /**
     * Convergence T076 (FR-011): an authorised validate request the router refuses before the endpoint runs (406 on
     * Accept) is recorded once as rejected; one refused for its key (401) is not.
     */
    @Test
    void aRouterLevelRefusalIsRecorded(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "db", null)
                .compose(a -> Future.all(
                        validateRequest(AppHarness.VALIDATOR_AUTH).putHeader("Accept", "text/plain").sendBuffer(Buffer.buffer(PATIENT)),
                        validateRequest(null).putHeader("Accept", "text/plain").sendBuffer(Buffer.buffer(PATIENT))))
                .compose(responses -> {
                    ctx.verify(() -> {
                        assertEquals(406, ((HttpResponse<?>) responses.resultAt(0)).statusCode());
                        assertEquals(401, ((HttpResponse<?>) responses.resultAt(1)).statusCode());
                    });
                    return awaitRows(vertx, 1);
                })
                .compose(rows -> vertx.timer(500).compose(t -> usageRows()))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.size(), "only the authorised request");
                    assertEquals("rejected", rows.get(0).getString(3));
                    assertEquals(406, rows.get(0).getInteger(4));
                    assertEquals("R4", rows.get(0).getString(11));
                    assertEquals(Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID, rows.get(0).getString(0));
                    ctx.completeNow();
                })));
    }

    /** Code review regression: a request without a body records 0 bytes, never -1. */
    @Test
    void aRequestWithoutABodyRecordsZeroBytes(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "db", null)
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).send())
                .compose(r -> awaitRows(vertx, 1))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(0, rows.get(0).getInteger(14), "request_bytes");
                    ctx.completeNow();
                })));
    }

    @Test
    void aTimedOutValidationIsRecordedOnceWithItsCpuTime(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "db", 1_000)
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).putHeader("Content-Type", "application/json")
                        .sendBuffer(Buffer.buffer(TestPackages.patientBundle(8000))))
                .compose(r -> {
                    ctx.verify(() -> assertEquals(503, r.statusCode(), r.bodyAsString()));
                    // The row is written once the worker has finished, with its real CPU time.
                    return Await.until(vertx, 120_000, "the timed-out request's usage row", () -> usageRows().map(rows -> !rows.isEmpty()))
                            .compose(v -> vertx.timer(500)).compose(v -> usageRows());
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.size());
                    Row r = rows.get(0);
                    assertEquals("timeout", r.getString(3));
                    assertEquals(503, r.getInteger(4));
                    assertTrue(r.getLong(7) > 0, "cpu_time_ns > 0");
                    assertTrue(r.getInteger(5) >= r.getInteger(6), "total >= validate");
                    ctx.completeNow();
                })));
    }

    @Test
    void logSinkWritesOneJsonLinePerValidation(Vertx vertx, VertxTestContext ctx) {
        logs = LogCapture.start();
        start(vertx, "log", null)
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer(PATIENT)))
                .compose(r -> Await.until(vertx, 5_000, "a usage line", () -> Future.succeededFuture(!logs.from(LogUsageWriter.LOGGER).isEmpty())))
                .compose(v -> vertx.timer(300))
                .compose(v -> usageRows())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    List<String> lines = logs.from(LogUsageWriter.LOGGER).stream().map(LogCapture.Captured::message).distinct().toList();
                    assertEquals(1, lines.size(), lines.toString());
                    JsonObject json = new JsonObject(lines.get(0));
                    assertEquals("trial validator", json.getString("apiKeyName"));
                    assertEquals(Constant.TRIAL_ORG_ID, json.getString("orgId"));
                    assertEquals(0, rows.size(), "the log sink writes nothing to the table");
                    ctx.completeNow();
                })));
    }

    @Test
    void noneSinkKeepsNothingAndBothSinksKeepOneEach(Vertx vertx, VertxTestContext ctx) {
        logs = LogCapture.start();
        start(vertx, "none", null)
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer(PATIENT)))
                .compose(r -> vertx.timer(500))
                .compose(v -> usageRows())
                .compose(rows -> {
                    ctx.verify(() -> {
                        assertEquals(0, rows.size());
                        assertTrue(logs.from(LogUsageWriter.LOGGER).isEmpty());
                    });
                    return app.stop().compose(v -> start(vertx, "log,db", null));
                })
                .compose(a -> validateRequest(AppHarness.VALIDATOR_AUTH).sendBuffer(Buffer.buffer(PATIENT)))
                .compose(r -> awaitRows(vertx, 1))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.size());
                    assertEquals(1, logs.from(LogUsageWriter.LOGGER).stream().map(LogCapture.Captured::message).distinct().count());
                    ctx.completeNow();
                })));
    }
}
