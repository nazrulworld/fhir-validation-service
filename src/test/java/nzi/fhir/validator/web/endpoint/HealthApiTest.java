package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test cases for the HealthApi endpoints
 * 
 * @author Md Nazrul Islam
 */
@ExtendWith(VertxExtension.class)
class HealthApiTest extends BaseTestContainer {
    // Use a random port to avoid conflicts
    private int testPort;
    private Vertx vertx;
    private WebClient client;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Start containers if not already started
        startContainers();

        // Initialize Vertx
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        client = WebClient.create(vertx);

        RouterBuilder.create(vertx, "openapi.yaml")
                .onSuccess(routerBuilder -> {
                    HealthService healthService = new HealthService(vertx, pgPool);
                    new HealthApi(routerBuilder, vertx, healthService);
                    // Create the router from the builder
                    Router router = routerBuilder.createRouter();
                    // Use port 0 to get a random available port
                    vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(0)
                            .onSuccess(server -> {
                                testPort = server.actualPort();
                                System.out.println("Test server started on port " + testPort);
                                testContext.completeNow();
                            })
                            .onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        // Close resources
        if(hasPgPool()){
            Pool pool = getPgPool(vertx);
            pool.close();
            removePgPool();
        }

        if (vertx != null) {
            vertx.close()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testHealthEndpoint(VertxTestContext testContext) {
        client.get(testPort, "localhost", "/health")
                .as(BodyCodec.jsonObject())
                .send()
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();

                    // Check main health status
                    assertNotNull(responseBody.getString("status"));
                    // The overall status might be UP or DOWN depending on the services
                    assertNotNull(responseBody.getLong("timestamp"));

                    // Check postgres status
                    JsonObject postgres = responseBody.getJsonObject("postgres");
                    assertNotNull(postgres);
                    assertNotNull(postgres.getString("status"));
                    // If status is UP, check response time
                    if ("UP".equals(postgres.getString("status"))) {
                        assertNotNull(postgres.getLong("responseTime"));
                    }
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testLivenessEndpoint(VertxTestContext testContext) {
        client.get(testPort, "localhost", "/health/liveness")
                .as(BodyCodec.jsonObject())
                .send()
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();

                    // Check liveness status
                    assertEquals("UP", responseBody.getString("status"));
                    assertNotNull(responseBody.getLong("timestamp"));

                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
