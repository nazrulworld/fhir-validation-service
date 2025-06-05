package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.model.IGPackageIdentity;
import nzi.fhir.validator.web.service.FhirContextLoader;
import nzi.fhir.validator.web.service.FhirValidationService;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

/**
 * Test class for {@link ValidationApi}.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
class ValidationApiTest extends BaseTestContainer {

    private Vertx vertx;
    private Router router;
    private int port;

    @Mock
    private FhirValidationService mockValidationService;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
        port = 8080;

        // Setup mock validation service to return a success response
        Mockito.lenient().when(mockValidationService.validate(any(), any()))
                .thenReturn(io.vertx.core.Future.succeededFuture(new JsonObject().put("valid", true)));

        // Create a HashMap of mock validation services for each supported FHIR version
        HashMap<IGPackageIdentity, FhirValidationService> validationServices = new HashMap<>();
        validationServices.put(IGPackageIdentity.createIGPackageIdentityForCorePackage(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4)), mockValidationService);
        validationServices.put(IGPackageIdentity.createIGPackageIdentityForCorePackage(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4B)), mockValidationService);
        validationServices.put(IGPackageIdentity.createIGPackageIdentityForCorePackage(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R5)), mockValidationService);

        // Initialize the ValidationApi with mock services
        ValidationApi validationApi = new ValidationApi(vertx, validationServices);
        validationApi.includeRoutes(router);

        // Start the server
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port);
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

        client.post(port, "localhost", "/" + version.name() + "/validate")
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

        client.post(port, "localhost", "/INVALID/validate")
                .sendJsonObject(requestBody, testContext.succeeding(response -> testContext.verify(() -> {
                    // Verify response status code is 400 for an invalid version
                    assert response.statusCode() == 400;
                    testContext.completeNow();
                })));
    }
}