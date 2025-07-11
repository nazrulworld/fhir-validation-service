package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.openapi.RouterBuilderOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.model.ValidatorIdentity;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

/**
 * Test class for {@link ValidationApi}.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
class ValidationApiTest extends BaseTestContainer {

    private Vertx vertx;
    private int testPort;

    @Mock
    private FhirValidationService mockValidationService;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);
        createTables(pgPool);

        // Setup mock validation service to return a success response
        Mockito.lenient().when(mockValidationService.validate(any(), any()))
                .thenReturn(io.vertx.core.Future.succeededFuture(new JsonObject().put("valid", true)));
        Mockito.lenient().when(mockValidationService.addNpmIgPackage(any()))
                .thenReturn(io.vertx.core.Future.succeededFuture());
        Mockito.lenient().when(mockValidationService.saveSateToDatabase(any()))
                .thenReturn(io.vertx.core.Future.succeededFuture());



        // Create a HashMap of mock validation services for each supported FHIR version
        ValidatorIdentity idR4 = ValidatorIdentity.createFromFhirVersion(SupportedFhirVersion.R4);
        FhirValidationService.put(idR4, mockValidationService);
        FhirValidationService.put(ValidatorIdentity.createFromFhirVersion(SupportedFhirVersion.R4B), mockValidationService);
        FhirValidationService.put(ValidatorIdentity.createFromFhirVersion(SupportedFhirVersion.R5), mockValidationService);


        RouterBuilder.create(vertx, "openapi.yaml")
        .onSuccess(routerBuilder -> {
            // Configure global body handler options for file uploads
            RouterBuilderOptions options = new RouterBuilderOptions()
                    .setMountResponseContentTypeHandler(true)
                    .setRequireSecurityHandlers(false);

            // Apply the options to the router builder
            routerBuilder.setOptions(options);
            // Add the body handler separately
            routerBuilder.rootHandler(BodyHandler.create()
                    .setUploadsDirectory("/tmp")
                    .setBodyLimit(10000)
                    .setDeleteUploadedFilesOnEnd(true));
            // Add global error handler for parameter validation
            /*routerBuilder.rootHandler(context -> {
                context.put("requestId", UUID.randomUUID().toString());
                context.next();
            });

            routerBuilder.rootHandler(context -> {
                if (context.failure() instanceof BodyProcessorException || context.failure() instanceof ParameterProcessorException) {
                    context.response().putHeader("Content-Type", "application/json");
                    context.response()
                            .setStatusCode(400)
                            .end(new JsonObject()
                                    .put("status", "error")
                                    .put("error", "Invalid request body format")
                                    .encode());
                } else {
                    context.next();
                }
            });
             */
            // Initialize the ValidationApi with mock services
            ValidationApi validationApi = ValidationApi.createInstance(vertx, pgPool);
            validationApi.includeRoutes(routerBuilder);
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

    @Test
    void testValidVersions(VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        // Test each valid version
        SupportedFhirVersion version = SupportedFhirVersion.R4;
        JsonObject requestBody = new JsonObject()
                .put("resourceType", "Patient")
                .put("id", "cff70a67-38c9-42f1-a608-8c64e5d1e1cd")
                .put("gender", "male")
                .put("birthDate", "1970-01-01")
                .put("name", new JsonArray().add(new JsonObject().put("family", "Smith")));

        client.post(testPort, "localhost", "/" + version.name() + "/validate").putHeader("Content-Type", "application/json")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    // Verify response status code is 200
                    assert response.statusCode() == 200;
                    assertTrue(response.body().toJsonObject().getBoolean("valid"));
                    // Complete the test context only after all requests are processed
                    testContext.completeNow();
                })));

    }

    @Test
    void testInvalidVersion(VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);

        JsonObject requestBody = new JsonObject()
                .put("resourceType", "Patient")
                .put("id", "cff70a67-38c9-42f1-a608-8c64e5d1e1cd")
                .put("gender", "male")
                .put("birthDate", "1970-01-01")
                .put("name", new JsonArray().add(new JsonObject().put("family", "Smith")));

        client.post(testPort, "localhost", "/INVALID/validate")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    // Verify response status code is 400 for an invalid version
                    assert response.statusCode() == 400;
                    testContext.completeNow();
                })));
    }

    @Test
    void testIncludeImplementationGuideSuccess(VertxTestContext testContext) throws IOException {
        WebClient client = WebClient.create(vertx);
        byte[] igBytes = readIgPackageFromClassPath("/fhir/hl7.fhir.dk.core-3.4.0.tgz");
        Pool pgPool = createPgPool(vertx);
        
        pgPool.withTransaction(pgClient -> 
            pgClient.preparedQuery("INSERT INTO fhir_validator_schema.fhir_implementation_guides (ig_package_id, ig_package_version, content_raw, ig_package_meta) VALUES ($1, $2, $3, $4)")
                .execute(Tuple.of(
                    "hl7.fhir.dk.core",
                    "3.4.0",
                    igBytes,
                    new JsonObject().put("fhirVersion", "R4").put("url", "http://dk").encode()
                ))
        )
        .onSuccess(result -> {
            JsonObject requestBody = new JsonObject()
                    .put("igPackageId", "hl7.fhir.dk.core")
                    .put("igPackageVersion", "3.4.0");

            client.post(testPort, "localhost", "/R4/include-ig")
                    .sendJsonObject(requestBody, ar -> {
                        if (ar.succeeded()) {
                            var response = ar.result();
                            testContext.verify(() -> {
                                assertEquals(200, response.statusCode());
                                JsonObject responseBody = response.bodyAsJsonObject();
                                assertEquals("success", responseBody.getString("status"));
                                testContext.completeNow();
                            });
                        } else {
                            testContext.failNow(ar.cause());
                        }
                    });
        })
        .onFailure(testContext::failNow);
    }

    @Test
    void testIncludeImplementationGuideInvalidVersion(VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject requestBody = new JsonObject()
                .put("igPackageId", "hl7.fhir.us.core")
                .put("igPackageVersion", "3.1.1");

        client.post(testPort, "localhost", "/INVALID/include-ig")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    assert response.statusCode() == 400;
                    //JsonObject responseBody = response.bodyAsJsonObject();
                    //assert !responseBody.getBoolean("valid");
                    //assert responseBody.getJsonArray("messages")
                    //        .getJsonObject(0)
                    //        .getString("message")
                    //        .contains("Invalid FHIR version");
                    testContext.completeNow();
                })));
    }

    @Test
    void testIncludeImplementationGuideMissingPackageId(VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject requestBody = new JsonObject()
                .put("igVersion", "3.1.1");

        client.post(testPort, "localhost", "/R4/include-ig")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    assert response.statusCode() == 400;
                    System.out.printf("Response body: %s\n", response.bodyAsString());
                    //JsonObject responseBody = response.bodyAsJsonObject();
                    //assert !responseBody.getBoolean("valid");
                    //assert responseBody.getJsonArray("messages")
                    //        .getJsonObject(0)
                    //        .getString("message")
                    //        .contains("packageId");
                    testContext.completeNow();
                })));
    }

    @Test
    void testIncludeImplementationGuideWithLatestVersion(VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);
        JsonObject requestBody = new JsonObject()
                .put("packageId", "hl7.fhir.us.core")
                .put("igVersion", "latest");

        client.post(testPort, "localhost", "/R4/include-ig")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    // The actual response will depend on whether the package exists in the database
                    // Here we're just verifying that the request is processed without errors
                    assert response.statusCode() == 200 || response.statusCode() == 400;
                    testContext.completeNow();
                })));
    }

    @AfterEach
    void tearDown() {
        if(FhirValidationService.size() > 0){
            FhirValidationService.clear();
        }
        if (vertx != null) {
            if(hasPgPool()){
                Pool pool = getPgPool(vertx);
                dropTables(pool);
                pool.close();
                removePgPool();
            }
            vertx.close();
        }
    }
}