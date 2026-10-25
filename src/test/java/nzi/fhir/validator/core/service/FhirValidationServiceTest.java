package nzi.fhir.validator.core.service;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class FhirValidationServiceTest extends BaseTestContainer {

    private static final String VALID_PATIENT_JSON = """
            {
              "resourceType": "Patient",
              "id": "example",
              "text": {
                "status": "generated",
                "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Example Patient</div>"
              },
              "active": true,
              "name": [ { "use": "official", "family": "Smith", "given": ["John"] } ],
              "gender": "male",
              "birthDate": "1974-12-25"
            }
            """;

    private static final String INVALID_PATIENT_JSON = """
            { "resourceType": "Patient", "id": "example", "gender": "invalid_gender", "birthDate": "not-a-date" }
            """;

    private static final String VALID_PATIENT_XML = """
            <Patient xmlns="http://hl7.org/fhir"><id value="x"/><active value="true"/></Patient>
            """;

    private CoreHarness core;
    private FhirValidationService service;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        CoreHarness.start(vertx, connectOptions()).onComplete(ctx.succeeding(h -> {
            core = h;
            service = h.services().validationService();
            ctx.completeNow();
        }));
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private static boolean hasErrors(ca.uhn.fhir.validation.ValidationResult r) {
        return r.getMessages().stream().anyMatch(m -> m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal());
    }

    @Test
    @DisplayName("Valid R4 Patient validates without errors")
    void validPatient(VertxTestContext ctx) {
        service.validate(SupportedFhirVersion.R4, VALID_PATIENT_JSON, SupportedContentType.JSON, List.of())
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(o.result().isSuccessful(), o.result().getMessages().toString());
                    assertFalse(hasErrors(o.result()));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Invalid R4 Patient reports errors")
    void invalidPatient(VertxTestContext ctx) {
        service.validate(SupportedFhirVersion.R4, INVALID_PATIENT_JSON, SupportedContentType.FHIR_JSON, List.of())
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertFalse(o.result().isSuccessful());
                    assertTrue(hasErrors(o.result()));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Explicit base profile is applied and reported")
    void explicitProfile(VertxTestContext ctx) {
        String base = "http://hl7.org/fhir/StructureDefinition/Patient";
        service.validate(SupportedFhirVersion.R4, VALID_PATIENT_JSON, SupportedContentType.JSON, List.of(base))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(o.result().isSuccessful());
                    assertTrue(o.appliedProfiles().contains(base));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("XML content is parsed as XML")
    void xmlContent(VertxTestContext ctx) {
        service.validate(SupportedFhirVersion.R4, VALID_PATIENT_XML, SupportedContentType.FHIR_XML, List.of())
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertFalse(hasErrors(o.result()), o.result().getMessages().toString());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Malformed JSON fails with a parse error")
    void malformedJson(VertxTestContext ctx) {
        service.validate(SupportedFhirVersion.R4, "{invalid_json}", SupportedContentType.JSON, List.of())
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(DataFormatException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Every enabled FHIR version has a working validator, including STU3")
    void everyEnabledVersionValidates(VertxTestContext ctx) {
        String patient = "{\"resourceType\":\"Patient\",\"active\":true}";
        List<Future<?>> all = SupportedFhirVersion.enabled().stream()
                .<Future<?>>map(v -> service.validate(v, patient, SupportedContentType.JSON, List.of())
                        .map(o -> {
                            assertEquals(v, o.version());
                            assertFalse(hasErrors(o.result()), v + ": " + o.result().getMessages());
                            return o;
                        }))
                .toList();
        Future.all(all).onComplete(ctx.succeedingThenComplete());
    }
}
