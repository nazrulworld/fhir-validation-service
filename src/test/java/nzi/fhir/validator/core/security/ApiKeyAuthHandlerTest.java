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
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, true, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.post("/R4/include-ig").handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
            for (int i = 0; i < 5 * MAX_CACHED; i++) {
                calls.add(client.post(server.actualPort(), "localhost", "/R4/include-ig")
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
}
