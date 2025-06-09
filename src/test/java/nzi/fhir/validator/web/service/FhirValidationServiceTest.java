package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.model.IGPackageIdentity;
import nzi.fhir.validator.model.ValidationRequestContext;
import nzi.fhir.validator.model.ValidationRequestOptions;
import nzi.fhir.validator.web.enums.SupportedContentType;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class FhirValidationServiceTest extends BaseTestContainer {

    private Vertx vertx;
    private FhirValidationService validationService;
    private ProfileService profileService;
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
              "birthDate": "1974-12-25"
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
                nzi.fhir.validator.web.enums.SupportedFhirVersion.R4,
                SupportedContentType.JSON,
                new ValidationRequestOptions(null)
        );
        return context;
    }
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);
        createTables(pgPool);
        profileService = ProfileService.create(vertx, FhirContext.forR4(), pgPool);
        
        IGPackageIdentity igPackageIdentity = new IGPackageIdentity(
            IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME, 
            "4.0.1", 
            SupportedFhirVersion.R4
        );

        FhirValidationService.create(vertx, igPackageIdentity, null, profileService)
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
                (ArrayList<String>) List.of("http://hl7.org/fhir/StructureDefinition/Patient")
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
            .onComplete(testContext.failing(throwable -> {
                testContext.verify(() -> {
                    assertNotNull(throwable);
                    assertTrue(throwable instanceof RuntimeException);
                    testContext.completeNow();
                });
            }));
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            FhirValidationService.clear();
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