package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.RequestIdHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Constitution VI regression: the key lookup cache is bounded, however many distinct tokens callers send.
 */
@ExtendWith(VertxExtension.class)
class ApiKeyAuthHandlerTest {
    private static final int MAX_CACHED = 10;

    @Test
    void manyDistinctInvalidKeysDoNotGrowTheCacheBeyondItsBound(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.post("/R4/validator").handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
            for (int i = 0; i < 5 * MAX_CACHED; i++) {
                calls.add(client.post(server.actualPort(), "localhost", "/R4/validator")
                        .putHeader("Authorization", "Bearer invalid-key-%040d".formatted(i))
                        .send());
            }
            return Future.all(calls).map(cf -> cf.<HttpResponse<Buffer>>list());
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> assertEquals(401, r.statusCode()));
            assertTrue(auth.cachedKeys() <= MAX_CACHED, "cache holds " + auth.cachedKeys() + " entries");
            assertTrue(auth.cachedKeys() > 0, "lookups are still cached");
            ctx.completeNow();
        })));
    }

    /**
     * Spec 005 research R3: every authorised request carries its {@link Caller}; open paths the anonymous one. Spec 008:
     * validation without a key is refused.
     */
    @Test
    void callerIsPutIntoTheContext(Vertx vertx, VertxTestContext ctx) {
        java.util.UUID org = java.util.UUID.randomUUID();
        String key = "acme-key-0123456789abcdefghijklmnopqrstu";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(
                new ApiKeyRepository.KeyInfo(java.util.UUID.randomUUID(), "acme-key", ApiKeyRepository.Role.VALIDATE, org, "acme", true, null))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> echo = rc -> {
            Caller c = ApiKeyAuthHandler.caller(rc);
            rc.response().end(c.anonymous() ? "anonymous" : c.orgId() + "|" + c.orgShortName() + "|" + c.role() + "|" + c.system());
        };
        router.post("/R4/validate").handler(echo);
        router.get("/health").handler(echo);
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return Future.all(
                    client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send(),
                    client.post(server.actualPort(), "localhost", "/R4/validate").send(),
                    client.get(server.actualPort(), "localhost", "/health").send());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            HttpResponse<Buffer> keyed = cf.resultAt(0);
            HttpResponse<Buffer> noKey = cf.resultAt(1);
            HttpResponse<Buffer> open = cf.resultAt(2);
            assertEquals(org + "|acme|VALIDATE|false", keyed.bodyAsString());
            assertEquals(401, noKey.statusCode(), noKey.bodyAsString());
            assertEquals("anonymous", open.bodyAsString());
            ctx.completeNow();
        })));
    }

    /** Spec 005 FR-014: a valid key of a deactivated organisation is refused with 403. */
    @Test
    void keyOfAnInactiveOrganisationIsForbidden(Vertx vertx, VertxTestContext ctx) {
        String key = "acme-key-0123456789abcdefghijklmnopqrstu";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(new ApiKeyRepository.KeyInfo(
                java.util.UUID.randomUUID(), "acme-admin", ApiKeyRepository.Role.ADMIN, java.util.UUID.randomUUID(), "acme", false, null))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return Future.all(
                    client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send(),
                    client.post(server.actualPort(), "localhost", "/R4/validator").putHeader("Authorization", "Bearer " + key).send(),
                    client.patch(server.actualPort(), "localhost", "/igs/a/1.0.0").putHeader("Authorization", "Bearer " + key).send());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            for (int i = 0; i < 3; i++) {
                HttpResponse<Buffer> r = cf.resultAt(i);
                assertEquals(403, r.statusCode(), r.bodyAsString());
                assertTrue(r.bodyAsString().contains("deactivated"));
            }
            ctx.completeNow();
        })));
    }

    /** Spec 008 FR-001, SC-002: a bearer value that can't be a key is refused without hashing, caching or a lookup. */
    @Test
    void malformedTokenIsRefusedWithoutLookup(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);
        List<String> malformed = List.of("x", "a b", "a".repeat(31), "a".repeat(129), "\u00fc".repeat(40), "abc.def-0123456789abcdefghijklmnopqrstu");

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
            for (String token : malformed) {
                calls.add(client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + token).send());
            }
            return Future.all(calls).map(cf -> cf.<HttpResponse<Buffer>>list());
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> {
                assertEquals(401, r.statusCode(), r.bodyAsString());
                assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
            });
            verifyNoInteractions(repository);
            assertEquals(0, auth.cachedKeys());
            ctx.completeNow();
        })));
    }

    /** A well-formed but unknown key is still looked up (once, then cached). */
    @Test
    void wellFormedUnknownKeyIsLookedUp(Vertx vertx, VertxTestContext ctx) {
        String key = "unknown-key-0123456789abcdefghijklmnopqr";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.empty()));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send()
                    .compose(first -> client.post(server.actualPort(), "localhost", "/R4/validate")
                            .putHeader("Authorization", "Bearer " + key).send().map(second -> List.of(first, second)));
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> assertEquals(401, r.statusCode()));
            verify(repository, times(1)).findActive(key);
            ctx.completeNow();
        })));
    }

    /** Spec 008 FR-006, SC-005: a cached key is looked up again once it expires, whatever the cache TTL. */
    @Test
    void cacheEntryNeverOutlivesKeyExpiry(Vertx vertx, VertxTestContext ctx) {
        String key = "expiring-key-0123456789abcdefghijklmnopq";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(new ApiKeyRepository.KeyInfo(
                java.util.UUID.randomUUID(), "expiring", ApiKeyRepository.Role.VALIDATE, java.util.UUID.randomUUID(), "acme",
                true, java.time.Instant.now().plusMillis(150)))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            java.util.function.Supplier<Future<HttpResponse<Buffer>>> call = () -> client.post(server.actualPort(), "localhost",
                    "/R4/validate").putHeader("Authorization", "Bearer " + key).send();
            return call.get().compose(first -> call.get())
                    .compose(cached -> vertx.timer(250).compose(t -> call.get()));
        }).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            verify(repository, times(2)).findActive(key);
            ctx.completeNow();
        })));
    }
}
