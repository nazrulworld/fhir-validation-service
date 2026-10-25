package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP contract of {@code /{version}/register-profile} (FR-021, B15).
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ProfileApiContractTest extends BaseTestContainer {

    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD))
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<HttpResponse<Buffer>> register(String version, JsonObject profile) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/" + version + "/register-profile")).timeout(5_000).sendJsonObject(profile);
    }

    @Test
    void registersProfileAndReportsRevision(VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/api-registered";
        register("R4", TestPackages.requiredPatientElements(url, "birthDate"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals("success", body.getString("status"));
                    assertEquals(url, body.getString("profileUrl"));
                    assertNotNull(body.getLong("revision"));
                    ctx.completeNow();
                })));
    }

    @Test
    void unparseableProfileIs400Problem(VertxTestContext ctx) {
        register("R4", new JsonObject().put("resourceType", "NoSuchType").put("url", "http://example.org/x"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 400, "invalid-request");
                    assertTrue(p.getString("detail").contains("Failed to parse profile"));
                    ctx.completeNow();
                })));
    }

    @Test
    void mismatchedFhirVersionIs400(VertxTestContext ctx) {
        JsonObject r5 = TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/r5p", "gender").put("fhirVersion", "5.0.0");
        register("R4", r5)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void disabledVersionIsRejectedNotMappedToR4(VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/r6";
        register("R6", TestPackages.requiredPatientElements(url, "gender"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    assertNull(app.bootstrap().services().profileServices().get(SupportedFhirVersion.R4).getProfile(url));
                    ctx.completeNow();
                })));
    }

    @Test
    void stu3ProfileIsAccepted(VertxTestContext ctx) {
        JsonObject stu3 = TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/stu3p", "gender")
                .put("fhirVersion", "3.0.2");
        register("STU3", stu3)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    ctx.completeNow();
                })));
    }
}
