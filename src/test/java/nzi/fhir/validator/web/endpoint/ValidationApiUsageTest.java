package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.openapi.RouterBuilderOptions;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.usage.UsageRecord;
import nzi.fhir.validator.core.usage.UsageWriter;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.RequestIdHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Code review regression (spec 008 FR-011): an unexpected exception in the validation endpoint still produces exactly
 * one usage record instead of leaving the meter open.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class ValidationApiUsageTest {

    @Test
    void anUnexpectedExceptionIsStillRecordedOnce(Vertx vertx, VertxTestContext ctx) {
        FhirValidationService service = mock(FhirValidationService.class);
        when(service.validate(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        ValidationApi api = new ValidationApi(service);
        api.setUsageWriter(new UsageWriter() {
            @Override
            public void write(UsageRecord record) {
                records.add(record);
            }

            @Override
            public Future<Void> close() {
                return Future.succeededFuture();
            }
        });
        Caller caller = Caller.of(UUID.randomUUID(), "acme", ApiKeyRepository.Role.VALIDATE, UUID.randomUUID(), "acme-ci");

        RouterBuilder.create(vertx, "openapi.yaml")
                .compose(builder -> {
                    builder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));
                    builder.rootHandler(new RequestIdHandler());
                    builder.rootHandler(BodyHandler.create());
                    builder.rootHandler(rc -> {
                        rc.put(ApiKeyAuthHandler.CALLER_KEY, caller);
                        rc.next();
                    });
                    api.includeRoutes(builder);
                    io.vertx.ext.web.Router router = builder.createRouter();
                    ErrorResponder.install(router);
                    return vertx.createHttpServer().requestHandler(router).listen(0);
                })
                .compose(server -> WebClient.create(vertx).post(server.actualPort(), "localhost", "/R4/validate")
                        .putHeader("Content-Type", "application/fhir+json")
                        .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}")))
                .compose(r -> vertx.timer(300).map(r))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(500, r.statusCode(), r.bodyAsString());
                    assertEquals(1, records.size(), "exactly one record: " + records);
                    assertEquals("error", records.get(0).outcome());
                    assertEquals(500, records.get(0).httpStatus());
                    assertEquals("acme-ci", records.get(0).apiKeyName());
                    ctx.completeNow();
                })));
    }

    /**
     * Code review regression: when the request timeout answered before this handler ran (a slow upload or key lookup),
     * no validation is started and the request is still recorded once, as a timeout.
     */
    @Test
    void aRequestTheTimeoutAlreadyAnsweredIsRecordedAndNotValidated(Vertx vertx, VertxTestContext ctx) {
        FhirValidationService service = mock(FhirValidationService.class);
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        ValidationApi api = new ValidationApi(service);
        api.setUsageWriter(new UsageWriter() {
            @Override
            public void write(UsageRecord record) {
                records.add(record);
            }

            @Override
            public Future<Void> close() {
                return Future.succeededFuture();
            }
        });
        Caller caller = Caller.of(UUID.randomUUID(), "acme", ApiKeyRepository.Role.VALIDATE, UUID.randomUUID(), "acme-ci");

        RouterBuilder.create(vertx, "openapi.yaml")
                .compose(builder -> {
                    builder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));
                    builder.rootHandler(new RequestIdHandler());
                    builder.rootHandler(BodyHandler.create());
                    builder.rootHandler(rc -> {
                        rc.put(ApiKeyAuthHandler.CALLER_KEY, caller);
                        // What RequestTimeoutHandler does when it fires: answer 503, flag the context; the chain goes on.
                        rc.put(nzi.fhir.validator.web.RequestTimeoutHandler.TIMED_OUT, true);
                        rc.response().setStatusCode(503).end();
                        rc.next();
                    });
                    api.includeRoutes(builder);
                    return vertx.createHttpServer().requestHandler(builder.createRouter()).listen(0);
                })
                .compose(server -> WebClient.create(vertx).post(server.actualPort(), "localhost", "/R4/validate")
                        .putHeader("Content-Type", "application/fhir+json")
                        .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}")))
                .compose(r -> vertx.timer(300).map(r))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(503, r.statusCode());
                    org.mockito.Mockito.verifyNoInteractions(service);
                    assertEquals(1, records.size(), "exactly one record: " + records);
                    assertEquals("timeout", records.get(0).outcome());
                    ctx.completeNow();
                })));
    }

    private static UsageWriter collecting(List<UsageRecord> records) {
        return new UsageWriter() {
            @Override
            public void write(UsageRecord record) {
                records.add(record);
            }

            @Override
            public Future<Void> close() {
                return Future.succeededFuture();
            }
        };
    }

    /** A router like production's: the meter is started by the root handler right after authorisation. */
    private static Future<Integer> serve(Vertx vertx, ValidationApi api, Caller caller,
                                         io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> beforeMeter,
                                         io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> afterMeter) {
        return RouterBuilder.create(vertx, "openapi.yaml").compose(builder -> {
            builder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false).setMountResponseContentTypeHandler(true));
            builder.rootHandler(new RequestIdHandler());
            builder.rootHandler(BodyHandler.create());
            builder.rootHandler(rc -> {
                rc.put(ApiKeyAuthHandler.CALLER_KEY, caller);
                beforeMeter.handle(rc);
            });
            builder.rootHandler(api.usageFallbackHandler());
            builder.rootHandler(afterMeter);
            api.includeRoutes(builder);
            return vertx.createHttpServer().requestHandler(builder.createRouter()).listen(0).map(s -> s.actualPort());
        });
    }

    /**
     * Code review regression: a request the router refuses (406) is timed from when it entered the service, not from
     * when its response ended.
     */
    @Test
    void aRouterRefusalIsTimedFromEntry(Vertx vertx, VertxTestContext ctx) {
        FhirValidationService service = mock(FhirValidationService.class);
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        ValidationApi api = new ValidationApi(service);
        api.setUsageWriter(collecting(records));
        Caller caller = Caller.of(UUID.randomUUID(), "acme", ApiKeyRepository.Role.VALIDATE, UUID.randomUUID(), "acme-ci");
        serve(vertx, api, caller, io.vertx.ext.web.RoutingContext::next,
                rc -> vertx.setTimer(300, id -> rc.next())) // 300 ms spent after the meter started
                .compose(port -> WebClient.create(vertx).post(port, "localhost", "/R4/validate")
                        .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", "text/plain")
                        .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}")))
                .compose(r -> vertx.timer(300).map(r))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(406, r.statusCode());
                    assertEquals(1, records.size(), "exactly one record: " + records);
                    assertEquals("rejected", records.get(0).outcome());
                    assertTrue(records.get(0).totalMs() >= 290, "timed from entry: " + records.get(0).totalMs() + " ms");
                    org.mockito.Mockito.verifyNoInteractions(service);
                    ctx.completeNow();
                })));
    }

    /**
     * Code review regression: the timeout answered during the key lookup and the router then refuses the request:
     * still exactly one record (timeout), and nothing is validated.
     */
    @Test
    void aTimeoutDuringTheKeyLookupFollowedByARouterRefusalIsRecordedOnce(Vertx vertx, VertxTestContext ctx) {
        FhirValidationService service = mock(FhirValidationService.class);
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        ValidationApi api = new ValidationApi(service);
        api.setUsageWriter(collecting(records));
        Caller caller = Caller.of(UUID.randomUUID(), "acme", ApiKeyRepository.Role.VALIDATE, UUID.randomUUID(), "acme-ci");
        serve(vertx, api, caller, rc -> {
                    rc.put(nzi.fhir.validator.web.RequestTimeoutHandler.TIMED_OUT, true);
                    rc.response().setStatusCode(503).end();
                    rc.next();
                }, io.vertx.ext.web.RoutingContext::next)
                .compose(port -> WebClient.create(vertx).post(port, "localhost", "/R4/validate")
                        .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", "text/plain")
                        .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}")))
                .compose(r -> vertx.timer(300).map(r))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(503, r.statusCode());
                    assertEquals(1, records.size(), "exactly one record: " + records);
                    assertEquals("timeout", records.get(0).outcome());
                    org.mockito.Mockito.verifyNoInteractions(service);
                    ctx.completeNow();
                })));
    }
}
