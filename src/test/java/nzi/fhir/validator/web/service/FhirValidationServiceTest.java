package nzi.fhir.validator.web.service;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.model.IGPackageIdentity;
import nzi.fhir.validator.model.ValidationRequestContext;
import nzi.fhir.validator.model.ValidationRequestOptions;
import nzi.fhir.validator.model.ValidatorIdentity;
import nzi.fhir.validator.web.enums.SupportedContentType;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class FhirValidationServiceTest extends BaseTestContainer {

    private Vertx vertx;
    private FhirValidationService validationService;
    private ProfileService profileService;
    private IgPackageService igPackageService;
    private static final String VALID_PATIENT_JSON = """
            {
              "resourceType": "Patient",
              "id": "example",
              "text": {
                "status": "generated",
                "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Example Patient</div>"
              },
              "active": true,
              "name": [
                {
                  "use": "official",
                  "family": "Smith",
                  "given": ["John"]
                }
              ],
              "gender": "male",
              "birthDate": "1974-12-25",
              "identifier": [
                  {
                    "use": "usual",
                    "type": {
                      "coding": [
                        {
                          "system": "http://terminology.hl7.org/CodeSystem/v2-0203",
                          "code": "MR"
                        }
                      ]
                    },
                    "system": "urn:oid:0.1.2.3.4.5.6.7",
                    "value": "123456"
                  }
                ]
            }
            """;

    private static final String INVALID_PATIENT_JSON = """
            {
              "resourceType": "Patient",
              "id": "example",
              "gender": "invalid_gender",
              "birthDate": "not-a-date"
            }
            """;

    private static ValidationRequestContext createValidationRequestContext(SupportedFhirVersion fhirVersion) {
        ValidationRequestContext context = new ValidationRequestContext(
                SupportedContentType.JSON,
                SupportedFhirVersion.R4,
                SupportedContentType.JSON,
                new ValidationRequestOptions(new ArrayList<>())
        );
        return context;
    }
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);
        createTables(pgPool);
        profileService = ProfileService.create(vertx, FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4), pgPool);
        igPackageService = IgPackageService.create(vertx, pgPool);
        IGPackageIdentity igPackageIdentity = new IGPackageIdentity(
            IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME, 
            "4.0.1", 
            SupportedFhirVersion.R4
        );

        FhirValidationService.create(vertx, SupportedFhirVersion.R4, igPackageService, profileService)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    validationService = ar.result();
                    testContext.completeNow();
                } else {
                    testContext.failNow(ar.cause());
                }
            });


    }

    @Test
    @DisplayName("Should validate valid FHIR Patient resource")
    void whenValidateValidPatient_thenSucceeds(VertxTestContext testContext) {

        ValidationRequestContext context = createValidationRequestContext(SupportedFhirVersion.R4);

        validationService.validate(VALID_PATIENT_JSON, context)
            .onComplete(testContext.succeeding(result -> {
                testContext.verify(() -> {
                    assertTrue(result.getBoolean("valid"));
                    JsonArray messages = result.getJsonArray("messages");
                    assertTrue(messages.isEmpty());
                    testContext.completeNow();
                });
            }));


    }

    @Test
    @EnabledIf("hasInternetConnection")
    @DisplayName("Should validate valid FHIR Patient resource against Danish IG")
    void whenValidateValidPatientForDanishIG_thenSucceeds(VertxTestContext testContext) throws IOException {
        String DANISH_IG_PATH = "fhir/hl7.fhir.dk.core-3.4.0.tgz";
        String DANISH_PATIENT_PATH = "fhir/resources/dk/Patient.json";
        byte[] dkIgPackageBytes = loadBytesFromClasspath(DANISH_IG_PATH);
        NpmPackage npmPackage = igPackageService.registerIg(dkIgPackageBytes, true).toCompletionStage().toCompletableFuture().join();
        // Load and register a profile
        assertNotNull(npmPackage);
        assertEquals("hl7.fhir.dk.core", npmPackage.name());
        assertEquals("3.4.0", npmPackage.version());
        testContext.completeNow();
        IGPackageIdentity igPackageIdentity2 = new IGPackageIdentity(
                npmPackage.name(),
                npmPackage.version(),
                SupportedFhirVersion.R4
        );
        ValidatorIdentity validatorIdentity = new ValidatorIdentity(npmPackage.name() +"#"+npmPackage.version(), SupportedFhirVersion.R4);
        FhirValidationService dkValidator = FhirValidationService.create(vertx, validatorIdentity, igPackageService, profileService, igPackageIdentity2).toCompletionStage().toCompletableFuture().join();

        // Prepare validation context and validate patient
        ValidationRequestContext context = createValidationRequestContext(SupportedFhirVersion.R4);
        String patientContent = null;
        try {
            patientContent = loadJsonFromClasspath(DANISH_PATIENT_PATH);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        dkValidator.validate(patientContent, context).onComplete(testContext.succeeding(result -> {
            testContext.verify(() -> {
                assertTrue(result.getBoolean("valid"));
                JsonArray messages = result.getJsonArray("messages");
                assertTrue(messages.isEmpty());
            });
        }));
        // Should be valid without dk profile on it, even though this is standard FHIR JSON
        dkValidator.validate(VALID_PATIENT_JSON, context).onComplete(testContext.succeeding(result -> {
            testContext.verify(() -> {
                assertTrue(result.getBoolean("valid"));
                JsonArray messages = result.getJsonArray("messages");
                assertTrue(messages.isEmpty());
            });
        }));

        // Should not be valid with dk profile on it, even though this is standard FHIR JSON
        // should be invalid??
        context.getValidationOptions().getProfilesToValidate().add("http://hl7.org/fhir/StructureDefinition/dk-core-patient");
        dkValidator.validate(VALID_PATIENT_JSON, context).onComplete(testContext.succeeding(result -> {
            testContext.verify(() -> {
                assertTrue(result.getBoolean("valid"));
                JsonArray messages = result.getJsonArray("messages");
                assertTrue(messages.isEmpty());
            });
        }));
    }

    /**
     * Loads JSON content from a classpath resource.
     *
     * @param resourcePath Path to the resource file
     * @return String containing the file contents
     * @throws IOException if the file cannot be read or doesn't exist
     */
    private String loadJsonFromClasspath(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("File not found in classpath: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private byte[] loadBytesFromClasspath(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("File not found in classpath: " + resourcePath);
            }
            return is.readAllBytes();
        }
    }

    private void logValidationMessages(JsonObject result) {
        System.out.printf("Messages: %s%n", result.getJsonArray("messages").encodePrettily());
    }
    @Test
    @DisplayName("Should fail validation for invalid FHIR Patient resource")
    void whenValidateInvalidPatient_thenFails(VertxTestContext testContext) {
        ValidationRequestContext context = createValidationRequestContext(SupportedFhirVersion.R4);

        validationService.validate(INVALID_PATIENT_JSON, context)
            .onComplete(testContext.succeeding(result -> {
                testContext.verify(() -> {
                    assertFalse(result.getBoolean("valid"));
                    JsonArray messages = result.getJsonArray("messages");
                    assertFalse(messages.isEmpty());
                    testContext.completeNow();
                });
            }));
    }

    @Test
    @DisplayName("Should validate resource against specified profiles")
    void whenValidateWithProfiles_thenValidatesAgainstProfiles(VertxTestContext testContext) {
        ValidationRequestOptions options = new ValidationRequestOptions(
                new ArrayList<>(List.of("http://hl7.org/fhir/StructureDefinition/Patient"))
        );
        ValidationRequestContext context = createValidationRequestContext(SupportedFhirVersion.R4);
        context.getValidationOptions().getProfilesToValidate().addAll(options.getProfilesToValidate());

        validationService.validate(VALID_PATIENT_JSON, context)
            .onComplete(testContext.succeeding(result -> {
                testContext.verify(() -> {
                    assertTrue(result.getBoolean("valid"));
                    testContext.completeNow();
                });
            }));
    }

    @Test
    @DisplayName("Should handle malformed JSON content")
    void whenValidateMalformedJson_thenThrowsException(VertxTestContext testContext) {
        String malformedJson = "{invalid_json}";
        ValidationRequestContext context = createValidationRequestContext(SupportedFhirVersion.R4);

        validationService.validate(malformedJson, context)
            .onComplete(result -> {
                assertFalse(result.result().getBoolean("valid"));
                assertTrue(result.result().toString().contains("HAPI-1861: Failed to parse JSON encoded FHIR content: Unexpected character ('i'"));
                testContext.completeNow();
            });
    }

    @AfterEach
    void tearDown() {
        if(FhirValidationService.size() > 0){
            FhirValidationService.clear();
        }
        if (vertx != null) {
            if (hasPgPool()) {
                Pool pool = getPgPool(vertx);
                dropTables(pool);
                pool.close();
                removePgPool();
            }
            vertx.close();
        }
    }
}