package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class ErrorResponderTest {

    private int port;
    private final AtomicInteger endCount = new AtomicInteger();

    @BeforeEach
    void start(Vertx vertx, VertxTestContext ctx) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.get("/throw").handler(rc -> {
            throw new RuntimeException("secret-internal");
        });
        router.get("/failed-future").handler(rc -> {
            rc.response().endHandler(v -> endCount.incrementAndGet());
            ErrorResponder.respond(rc, Future.failedFuture(new IllegalStateException("secret-internal")));
        });
        router.get("/problem").handler(rc ->
                ErrorResponder.respond(rc, Future.failedFuture(new ProblemException(404, "package-not-found", "Package x#1 is not stored"))));
        router.get("/ok").handler(rc ->
                ErrorResponder.respond(rc, Future.succeededFuture(new JsonObject().put("a", 1))));
        ErrorResponder.install(router);
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onComplete(ctx.succeeding(s -> {
                    port = s.actualPort();
                    ctx.completeNow();
                }));
    }

    private static void assertProblem(HttpResponse<Buffer> r, int status) {
        assertEquals(status, r.statusCode());
        assertTrue(r.getHeader("Content-Type").startsWith("application/problem+json"), r.getHeader("Content-Type"));
        JsonObject body = r.bodyAsJsonObject();
        assertNotNull(body.getString("type"));
        assertNotNull(body.getString("title"));
        assertEquals(status, body.getInteger("status"));
        assertNotNull(body.getString("correlationId"));
        assertEquals(r.getHeader("X-Request-Id"), body.getString("correlationId"));
        assertFalse(r.bodyAsString().contains("secret-internal"), r.bodyAsString());
        assertFalse(r.bodyAsString().contains("java."), r.bodyAsString());
    }

    @Test
    void thrownExceptionBecomesSanitised500(Vertx vertx, VertxTestContext ctx) {
        WebClient.create(vertx).get(port, "localhost", "/throw").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 500);
                    assertTrue(r.bodyAsJsonObject().getString("type").endsWith("/internal-error"));
                    ctx.completeNow();
                })));
    }

    @Test
    void failedFutureEndsResponseExactlyOnce(Vertx vertx, VertxTestContext ctx) {
        WebClient.create(vertx).get(port, "localhost", "/failed-future").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 500);
                    assertEquals(1, endCount.get());
                    ctx.completeNow();
                })));
    }

    @Test
    void problemExceptionKeepsStatusTypeAndCuratedDetail(Vertx vertx, VertxTestContext ctx) {
        WebClient.create(vertx).get(port, "localhost", "/problem").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404);
                    JsonObject body = r.bodyAsJsonObject();
                    assertTrue(body.getString("type").endsWith("/package-not-found"));
                    assertEquals("Package x#1 is not stored", body.getString("detail"));
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownRouteIsProblem404(Vertx vertx, VertxTestContext ctx) {
        WebClient.create(vertx).get(port, "localhost", "/nope").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404);
                    ctx.completeNow();
                })));
    }

    @Test
    void successIsJson(Vertx vertx, VertxTestContext ctx) {
        WebClient.create(vertx).get(port, "localhost", "/ok").putHeader("X-Request-Id", "abc12345-req")
                .send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode());
                    assertEquals(1, r.bodyAsJsonObject().getInteger("a"));
                    assertEquals("abc12345-req", r.getHeader("X-Request-Id"));
                    ctx.completeNow();
                })));
    }
}
