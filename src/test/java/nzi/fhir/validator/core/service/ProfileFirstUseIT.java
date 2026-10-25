package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1 regression: a stored profile is applied on the very first validation, and an updated profile immediately.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ProfileFirstUseIT extends BaseTestContainer {
    private static final String URL = "http://example.org/fhir/StructureDefinition/first-use-patient";

    private CoreHarness core;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) throws Exception {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO fhir_validator_schema.fhir_profiles (url, profile_json, fhir_version) VALUES (?, ?::jsonb, '4.0.1')")) {
            ps.setString(1, URL);
            ps.setString(2, TestPackages.requiredPatientElements(URL, "birthDate").encode());
            ps.executeUpdate();
        }
        CoreHarness.start(vertx, connectOptions()).onComplete(ctx.succeeding(h -> {
            core = h;
            ctx.completeNow();
        }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<ValidatorSnapshot.Outcome> validate() {
        return core.services().validationService().validate(SupportedFhirVersion.R4, TestPackages.minimalPatient(),
                SupportedContentType.JSON, List.of(URL));
    }

    @Test
    void storedProfileAppliesOnFirstValidationAndUpdatesImmediately(VertxTestContext ctx) {
        validate()
                .compose(first -> {
                    ctx.verify(() -> {
                        assertTrue(TestPackages.hasError(first, "birthDate"), "first validation must apply the stored profile: " + first.result().getMessages());
                        assertTrue(first.appliedProfiles().contains(URL));
                    });
                    JsonObject updated = TestPackages.requiredPatientElements(URL, "gender");
                    return core.services().profileServices().get(SupportedFhirVersion.R4).registerProfile(updated);
                })
                .compose(revision -> validate())
                .onComplete(ctx.succeeding(second -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(second, "gender"), "updated profile must apply immediately: " + second.result().getMessages());
                    assertFalse(TestPackages.hasError(second, "birthDate"), "old profile content must be gone: " + second.result().getMessages());
                    ctx.completeNow();
                })));
    }

    @Test
    void registeringForTheWrongFhirVersionIsRejected(VertxTestContext ctx) {
        JsonObject r5Profile = TestPackages.requiredPatientElements(URL, "gender").put("fhirVersion", "5.0.0");
        core.services().profileServices().get(SupportedFhirVersion.R4).registerProfile(r5Profile)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof nzi.fhir.validator.web.ProblemException);
                    assertEquals(400, ((nzi.fhir.validator.web.ProblemException) err).status());
                    ctx.completeNow();
                })));
    }
}
