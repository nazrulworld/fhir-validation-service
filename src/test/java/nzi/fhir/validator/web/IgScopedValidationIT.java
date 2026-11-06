package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Choosing the IG per validation request over HTTP (spec 004 US1, FR-001 to FR-009, SC-001, SC-002, SC-004).
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class IgScopedValidationIT extends BaseTestContainer {
    private static final String BASE = "http://example.org/fhir/StructureDefinition/";
    /** In A (both versions): requires birthDate. */
    private static final String PA = BASE + "scoped-a";
    /** Only in A#2.0.0: requires gender. */
    private static final String PA2 = BASE + "scoped-a2";
    /** In B: requires gender. */
    private static final String PB = BASE + "scoped-b";
    private static final String PATIENT_A = patientDeclaring(PA);

    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        AppHarness.start(vertx, config)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .compose(v -> {
                    ServiceRegistry services = app.bootstrap().services();
                    return TestPackages.storePackage(services, TestPackages.profilePackage("scoped.a", "1.0.0",
                                    TestPackages.requiredPatientElements(PA, "birthDate")))
                            .compose(x -> TestPackages.storePackage(services, TestPackages.profilePackage("scoped.a", "2.0.0",
                                    TestPackages.requiredPatientElements(PA, "birthDate"),
                                    TestPackages.requiredPatientElements(PA2, "gender"))))
                            .compose(x -> TestPackages.includePackage(services, TestPackages.profilePackage("scoped.b", "1.0.0",
                                    TestPackages.requiredPatientElements(PB, "gender")), "scoped.b", "1.0.0"))
                            .compose(x -> TestPackages.storePackage(services, TestPackages.igPackage("scoped.r5", "1.0.0",
                                    List.of("5.0.0"), Map.of())));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private static String patientDeclaring(String profile) {
        return new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true)
                .put("meta", new JsonObject().put("profile", new io.vertx.core.json.JsonArray().add(profile))).encode();
    }

    private HttpRequest<Buffer> validate(String version, String... igs) {
        HttpRequest<Buffer> req = app.client().post(app.port(), "localhost", "/" + version + "/validate").timeout(30_000)
                .putHeader("Content-Type", "application/fhir+json");
        for (String ig : igs) {
            req.addQueryParam("ig", ig);
        }
        return req;
    }

    private static boolean mentions(HttpResponse<Buffer> r, String text) {
        return r.bodyAsJsonObject().getJsonArray("messages").stream()
                .map(m -> ((JsonObject) m).getString("message") + " " + ((JsonObject) m).getString("location"))
                .anyMatch(m -> m.contains(text));
    }

    @Test
    void withoutIgOnlyTheCoreSpecificationApplies(VertxTestContext ctx) {
        validate("R4").sendBuffer(Buffer.buffer(PATIENT_A)).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            assertEquals(200, r.statusCode(), r.bodyAsString());
            assertEquals("base", r.getHeader("X-Validation-Context"));
            JsonObject body = r.bodyAsJsonObject();
            assertTrue(body.containsKey("igPackage") && body.getValue("igPackage") == null, body.encode());
            assertFalse(mentions(r, "birthDate"), "no IG-specific constraint applies without ig (SC-002)");
            assertEquals(List.of(), body.getJsonArray("appliedProfiles").getList(),
                    "a meta.profile no context defines is not applied");
            ctx.completeNow();
        })));
    }

    @Test
    void eachIgVersionAppliesOnlyItsOwnProfiles(VertxTestContext ctx) {
        String bothProfiles = patientDeclaring(PA2);
        Future.all(validate("R4", "scoped.a#1.0.0").sendBuffer(Buffer.buffer(PATIENT_A)),
                        validate("R4", "scoped.a#2.0.0").sendBuffer(Buffer.buffer(bothProfiles)),
                        validate("R4", "scoped.a#1.0.0").sendBuffer(Buffer.buffer(bothProfiles)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    HttpResponse<Buffer> v1 = all.resultAt(0);
                    HttpResponse<Buffer> v2 = all.resultAt(1);
                    HttpResponse<Buffer> v1WithV2Profile = all.resultAt(2);
                    assertEquals(200, v1.statusCode(), v1.bodyAsString());
                    assertEquals("scoped.a#1.0.0", v1.getHeader("X-Validation-Context"));
                    assertEquals("scoped.a#1.0.0", v1.bodyAsJsonObject().getString("igPackage"));
                    assertTrue(mentions(v1, "birthDate"));
                    assertTrue(mentions(v2, "gender"), "A#2.0.0's own profile applies");
                    assertEquals(List.of(PA2), v2.bodyAsJsonObject().getJsonArray("appliedProfiles").getList());
                    assertFalse(mentions(v1WithV2Profile, "gender"), "A#2.0.0 content is not visible through A#1.0.0 (SC-001)");
                    ctx.completeNow();
                })));
    }

    @Test
    void includedIgOfAnotherPackageIsNotVisible(VertxTestContext ctx) {
        validate("R4", "scoped.a#1.0.0").addQueryParam("profile", PB).sendBuffer(Buffer.buffer(PATIENT_A))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "unknown-profile");
                    assertEquals(List.of(PB), problem.getJsonArray("unresolvedProfiles").getList());
                    ctx.completeNow();
                })));
    }

    @Test
    void versionlessIgUsesTheHighestStoredVersionAndReportsIt(VertxTestContext ctx) {
        Future.all(validate("R4", "scoped.a").sendBuffer(Buffer.buffer(PATIENT_A)),
                        validate("R4", "scoped.a#latest").sendBuffer(Buffer.buffer(PATIENT_A)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < 2; i++) {
                        HttpResponse<Buffer> r = all.resultAt(i);
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals("scoped.a#2.0.0", r.getHeader("X-Validation-Context"));
                        assertEquals("scoped.a#2.0.0", r.bodyAsJsonObject().getString("igPackage"));
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownIgIs404AndNothingIsBuilt(VertxTestContext ctx) {
        long started = System.currentTimeMillis();
        validate("R4", "no.such.ig#1.0.0").sendBuffer(Buffer.buffer(PATIENT_A))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 404, "package-not-found");
                    assertTrue(problem.getString("detail").contains("no.such.ig#1.0.0"), problem.encode());
                    assertTrue(problem.getString("detail").contains("Register or upload it first"), problem.encode());
                    assertTrue(System.currentTimeMillis() - started < 1_000, "rejected in under a second (SC-004)");
                    assertNull(app.bootstrap().services().validatorRegistry().kind(
                            nzi.fhir.validator.core.validator.SnapshotKey.of(nzi.fhir.validator.core.enums.SupportedFhirVersion.R4,
                                    "no.such.ig#1.0.0")));
                    ctx.completeNow();
                })));
    }

    @Test
    void igForAnotherFhirVersionIs400NamingTheResolvedVersion(VertxTestContext ctx) {
        validate("R4", "scoped.r5").sendBuffer(Buffer.buffer(PATIENT_A))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertTrue(problem.getString("detail").contains("scoped.r5#1.0.0"), problem.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedOrRepeatedIgIs400(VertxTestContext ctx) {
        Future.all(List.of(
                        validate("R4", "a##b").sendBuffer(Buffer.buffer(PATIENT_A)),
                        validate("R4", "a#1,b#2").sendBuffer(Buffer.buffer(PATIENT_A)),
                        validate("R4", "a".repeat(257)).sendBuffer(Buffer.buffer(PATIENT_A)),
                        validate("R4", "scoped.a#1.0.0", "scoped.a#2.0.0").sendBuffer(Buffer.buffer(PATIENT_A))))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        HttpResponse<Buffer> r = all.resultAt(i);
                        // A 257-character value may be refused by the contract validator (maxLength) or by the handler.
                        assertEquals(400, r.statusCode(), "case " + i + ": " + r.bodyAsString());
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void checksRunInTheSpecifiedOrder(VertxTestContext ctx) {
        // A malformed ig wins over an empty body; an unknown ig wins over an unparseable body (FR-009). The empty body
        // is sent as XML: an empty JSON body is already refused by the contract layer before the handler runs.
        Future.all(validate("R4", "a##b").putHeader("Content-Type", "application/fhir+xml").sendBuffer(Buffer.buffer("")),
                        validate("R4", "no.such.ig#1.0.0").sendBuffer(Buffer.buffer("{\"resourceType\":\"Nope\"}")))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    HttpResponse<Buffer> malformed = all.resultAt(0);
                    assertEquals(400, malformed.statusCode());
                    assertTrue(malformed.bodyAsString().contains("ig parameter"), malformed.bodyAsString());
                    assertProblem(all.resultAt(1), 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    /** Convergence T071 (FR-009): a malformed ig also wins over an empty or malformed JSON body. */
    @Test
    void malformedIgWinsOverABodyTheContractLayerRefuses(VertxTestContext ctx) {
        Future.all(validate("R4", "a##b").sendBuffer(Buffer.buffer("")),
                        validate("R4", "a##b").sendBuffer(Buffer.buffer("{not json")))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < 2; i++) {
                        JsonObject problem = assertProblem(all.resultAt(i), 400, "invalid-request");
                        assertTrue(problem.getString("detail").contains("ig parameter"), problem.encode());
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void operationOutcomeCarriesTheContextHeaderAndAnUnchangedBody(VertxTestContext ctx) {
        validate("R4", "scoped.b#1.0.0").putHeader("Accept", "application/fhir+json").sendBuffer(Buffer.buffer(PATIENT_A))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("scoped.b#1.0.0", r.getHeader("X-Validation-Context"));
                    JsonObject oo = r.bodyAsJsonObject();
                    assertEquals("OperationOutcome", oo.getString("resourceType"));
                    assertFalse(oo.encode().contains("scoped.b#1.0.0"), "the OperationOutcome body is not altered");
                    ctx.completeNow();
                })));
    }
}
