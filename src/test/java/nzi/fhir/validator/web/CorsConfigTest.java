package nzi.fhir.validator.web;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.web.endpoint.ValidationApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 010 FR-010: browsers can read the X-Tx-* headers of a validation (CORS exposed headers). */
@ExtendWith(VertxExtension.class)
class CorsConfigTest {

    @Test
    void theTerminologyHeadersAreExposed(Vertx vertx, VertxTestContext ctx) {
        ApplicationConfig config = ApplicationConfig.of(new JsonObject().put("profile", "dev")
                .put("http", new JsonObject().put("cors", new JsonObject().put("allowedOrigins", "https://app.example.org"))));
        Router router = Router.router(vertx);
        router.route().handler(CorsConfig.create(config).orElseThrow());
        router.post("/R4/validate").handler(rc -> rc.response().end());
        vertx.createHttpServer().requestHandler(router).listen(0)
                .compose(server -> vertx.createHttpClient()
                        .request(HttpMethod.POST, server.actualPort(), "localhost", "/R4/validate")
                        .compose(req -> req.putHeader("Origin", "https://app.example.org").send())
                        .map(resp -> new Object[]{server, resp}))
                .onComplete(ctx.succeeding(pair -> ctx.verify(() -> {
                    HttpClientResponse resp = (HttpClientResponse) pair[1];
                    String exposed = resp.getHeader("Access-Control-Expose-Headers");
                    assertNotNull(exposed, "CORS answered");
                    for (String header : Set.of(ValidationApi.TX_MODE_HEADER, ValidationApi.TX_SERVER_HEADER,
                            ValidationApi.TX_SOURCE_HEADER)) {
                        assertTrue(exposed.contains(header), header + " in " + exposed);
                    }
                    ((HttpServer) pair[0]).close();
                    ctx.completeNow();
                })));
    }
}
