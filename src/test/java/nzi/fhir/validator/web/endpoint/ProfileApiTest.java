package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.sqlclient.PoolOptions;
import nzi.fhir.validator.web.service.ProfileService;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(VertxExtension.class)
class ProfileApiTest extends BaseTestContainer {
    // Use a random port to avoid conflicts
    private int testPort;
    private Vertx vertx;
    private WebClient client;
    private RedisAPI redis;
    private PgPool pgPool;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Start containers if not already started
        startContainers();

        // Initialize Vertx
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);

        // Configure Redis
        RedisOptions redisOptions = new RedisOptions()
                .setConnectionString(getRedisConnectionString());
        redis = RedisAPI.api(Redis.createClient(vertx, redisOptions));

        // Configure PostgreSQL
        PgConnectOptions pgConnectOptions = new PgConnectOptions()
                .setHost(getPostgresHost())
                .setPort(getPostgresPort())
                .setDatabase(POSTGRES_DATABASE)
                .setUser(POSTGRES_USERNAME)
                .setPassword(POSTGRES_PASSWORD);
        PoolOptions poolOptions = new PoolOptions().setMaxSize(5);
        pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptions);

        // Create and deploy the test server
        Router router = Router.router(vertx);
        ProfileService profileService = new ProfileService(vertx, "R4", redis, pgPool);
        new ProfileApi(router, vertx, profileService);

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
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        // Close resources
        if (pgPool != null) {
            pgPool.close();
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
    void testRegisterProfile(VertxTestContext testContext) {
        // Create a test profile
        String profileUrl = "http://example.org/fhir/StructureDefinition/test-profile";
        JsonObject profile = new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", profileUrl)
                .put("name", "TestProfile")
                .put("status", "active")
                .put("kind", "resource")
                .put("abstract", false)
                .put("type", "Patient")
                .put("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Patient")
                .put("derivation", "constraint");

        // Create a request body
        JsonObject requestBody = new JsonObject()
                .put("url", profileUrl)
                .put("profile", profile);

        // Send request to register profile
        client.post(testPort, "localhost", "/register-profile")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();
                    assertEquals("success", responseBody.getString("status"));
                    assertEquals(profileUrl, responseBody.getString("profileUrl"));

                    // Verify profile was stored in Redis
                    redis.get("profile:" + profileUrl.replaceAll("[^a-zA-Z0-9:]", "_"))
                            .onSuccess(redisResult -> {
                                assertNotNull(redisResult);
                                System.out.println("Profile found in Redis cache");

                                // Verify the profile was stored in PostgreSQL
                                pgPool.query("SELECT profile_json FROM fhir_profiles WHERE url = '" + profileUrl + "'")
                                        .execute()
                                        .onSuccess(rows -> {
                                            assertEquals(1, rows.size());
                                            System.out.println("Profile found in PostgreSQL database");
                                            testContext.completeNow();
                                        })
                                        .onFailure(testContext::failNow);
                            })
                            .onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testRegisterProfileWithInvalidRequest(VertxTestContext testContext) {
        // Create an invalid request (missing profile)
        JsonObject requestBody = new JsonObject()
                .put("url", "http://example.org/fhir/StructureDefinition/invalid-profile");

        // Send request to register profile
        client.post(testPort, "localhost", "/register-profile")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    // Verify response indicates error
                    assertEquals(400, response.statusCode());
                    JsonObject responseBody = response.body();
                    assertEquals("Missing url or profile", responseBody.getString("error"));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}