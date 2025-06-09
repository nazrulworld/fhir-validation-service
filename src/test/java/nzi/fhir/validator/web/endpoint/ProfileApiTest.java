package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nzi.fhir.validator.web.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Md Nazrul Islam
 */
@ExtendWith(VertxExtension.class)
class ProfileApiTest extends BaseTestContainer {
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
        initDatabaseScheme(pgPool);
        client = WebClient.create(vertx);

        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS %s.fhir_profiles (
                url TEXT NOT NULL,
                profile_json JSONB NOT NULL,
                fhir_version TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                modified_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (url, fhir_version)
            )
        """.formatted(DB_POSTGRES_SCHEMA_NAME);
        pgPool.query(createTableSQL)
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Create and deploy the test server
        Router router = Router.router(vertx);

        // Create ProfileApi with the production constructor
        ProfileApi profileApi = new ProfileApi(vertx, pgPool);

        // Configure routes
        profileApi.includeRoutes(router);

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
        if(hasPgPool()){
            Pool pool = getPgPool(vertx);
            pool.query("DROP TABLE IF EXISTS %s.fhir_profiles".formatted(DB_POSTGRES_SCHEMA_NAME))
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
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

        // Send request to register profile with R4 version
        client.post(testPort, "localhost", "/R4/register-profile")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(profile)
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();
                    assertEquals("success", responseBody.getString("status"));
                    assertEquals(profileUrl, responseBody.getString("profileUrl"));
                    testContext.completeNow();

                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testRegisterProfileWithInvalidRequest(VertxTestContext testContext) {
        // Create an invalid request (missing profile)
        JsonObject requestBody = new JsonObject()
                .put("url", "http://example.org/fhir/StructureDefinition/invalid-profile");

        // Send request to register profile with R4 version
        client.post(testPort, "localhost", "/R4/register-profile")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    // Verify response indicates error
                    assertEquals(400, response.statusCode());
                    JsonObject responseBody = response.body();
                    assertTrue(responseBody.getString("error").contains("Failed to parse profile"));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testRegisterProfileWithInvalidVersion(VertxTestContext testContext) {
        // Create a valid request
        String profileUrl = "http://example.org/fhir/StructureDefinition/test-profile";
        JsonObject profile = new JsonObject()
                .put("resourceType", "StructureDefinition")
                .put("url", profileUrl)
                .put("name", "TestProfile");

        JsonObject requestBody = new JsonObject()
                .put("url", profileUrl)
                .put("profile", profile);

        // Send a request with an invalid version
        client.post(testPort, "localhost", "/INVALID_VERSION/register-profile")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    // Verify response indicates error
                    assertEquals(400, response.statusCode());
                    JsonObject responseBody = response.body();
                    // Check that the error message contains information about an invalid version
                    assertTrue(responseBody.getString("error").contains("Invalid FHIR version"));
                    assertTrue(responseBody.getString("error").contains("Supported versions are"));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
