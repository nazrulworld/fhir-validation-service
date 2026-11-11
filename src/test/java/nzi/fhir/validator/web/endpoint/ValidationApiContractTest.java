package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP contract of {@code /{version}/validate} and {@code /{version}/validator} (FR-017 to FR-021, SC-005): header
 * variants, status mapping, error shape, and every request answered promptly.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ValidationApiContractTest extends BaseTestContainer {
    private static final String PATIENT = Fixtures.string(Fixtures.DK_PATIENT);
    private static final String INVALID_PATIENT = "{\"resourceType\":\"Patient\",\"gender\":\"invalid_gender\"}";
    private static final String XML_PATIENT = "<Patient xmlns=\"http://hl7.org/fhir\"><active value=\"true\"/></Patient>";

    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("http").put("maxBodyMb", 1);
        config.getJsonObject("validation").put("maxBundleEntries", 5);
        AppHarness.start(vertx, config)
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

    private HttpRequest<Buffer> post(String path) {
        HttpRequest<Buffer> req = app.client().post(app.port(), "localhost", path).timeout(5_000);
        // Spec 008: validation always needs a key (administrative calls add the admin key on top).
        return path.contains("/validate") && !path.contains("/validator") ? AppHarness.asValidator(req) : req;
    }

    private Future<HttpResponse<Buffer>> validate(String version, String contentType, String accept, String body) {
        HttpRequest<Buffer> req = post("/" + version + "/validate");
        if (contentType != null) {
            req.putHeader("Content-Type", contentType);
        }
        if (accept != null) {
            req.putHeader("Accept", accept);
        }
        return req.sendBuffer(Buffer.buffer(body));
    }

    private static void assertLegacyJson(HttpResponse<Buffer> r) {
        assertEquals(200, r.statusCode(), r.bodyAsString());
        assertTrue(r.getHeader("Content-Type").startsWith("application/json"), r.getHeader("Content-Type"));
        JsonObject body = r.bodyAsJsonObject();
        assertNotNull(body.getBoolean("valid"));
        assertNotNull(body.getJsonArray("messages"));
        assertNotNull(r.getHeader("X-Request-Id"));
    }

    @Test
    void wildcardAcceptAndCharsetContentTypeWork(VertxTestContext ctx) {
        validate("R4", "application/json; charset=utf-8", "*/*", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    assertEquals("R4", r.bodyAsJsonObject().getString("fhirVersion"));
                    ctx.completeNow();
                })));
    }

    @Test
    void responseNamesTheValidationContext(VertxTestContext ctx) {
        // Spec 004 FR-008: without ig the base context is used; an unknown ig is 404 package-not-found.
        validate("R4", "application/fhir+json", null, PATIENT)
                .compose(r -> {
                    ctx.verify(() -> {
                        assertLegacyJson(r);
                        assertEquals("base", r.getHeader("X-Validation-Context"));
                        JsonObject body = r.bodyAsJsonObject();
                        assertTrue(body.containsKey("igPackage") && body.getValue("igPackage") == null, body.encode());
                        // Spec 005 FR-020: the owner and sharing of the context, in the body and in headers.
                        assertEquals("system", r.getHeader("X-Validation-Context-Owner"));
                        assertEquals("true", r.getHeader("X-Validation-Context-Shareable"));
                        assertEquals("system", body.getString("igOwner"));
                        assertEquals(Boolean.TRUE, body.getBoolean("igShareable"));
                    });
                    return post("/R4/validate").addQueryParam("ig", "not.stored#1.0.0")
                            .putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(PATIENT));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    /** Spec 007 FR-022 (contracts §1): a stored IG that isn't included is a 400 problem, whatever Accept asks for. */
    @Test
    void aStoredButNotIncludedIgIs400(VertxTestContext ctx) {
        nzi.fhir.validator.testsupport.TestPackages.storePackage(app.bootstrap().services(),
                        nzi.fhir.validator.testsupport.TestPackages.profilePackage("contract.notincl", "1.0.0",
                                nzi.fhir.validator.testsupport.TestPackages.requiredPatientElements(
                                        "http://example.org/fhir/StructureDefinition/contract-notincl", "birthDate")))
                .compose(v -> post("/R4/validate").addQueryParam("ig", "contract.notincl#1.0.0")
                        .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", "application/fhir+xml")
                        .sendBuffer(Buffer.buffer(PATIENT)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject body = assertProblem(r, 400, "invalid-request");
                    assertEquals("IG package contract.notincl#1.0.0 is not included for R4; include it first "
                            + "(POST /R4/validator)", body.getString("detail"));
                    ctx.completeNow();
                })));
    }

    /** Spec 007 (contracts §1, §4): the contract no longer promises on-demand contexts. */
    @Test
    void theContractNoLongerDescribesOnDemandContexts() throws java.io.IOException {
        try (java.io.InputStream in = getClass().getResourceAsStream("/openapi.yaml")) {
            String yaml = new String(java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(yaml.contains("built on demand"), "openapi.yaml still mentions on-demand builds");
            assertFalse(yaml.contains("built on first use"));
            assertTrue(yaml.contains("Validation requests naming it are refused with 400 invalid-request"));
        }
    }

    @Test
    void absentAcceptDefaultsToJson(VertxTestContext ctx) {
        validate("R4", "application/fhir+json", null, PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    ctx.completeNow();
                })));
    }

    @Test
    void fhirXmlAcceptReturnsOperationOutcomeXml(VertxTestContext ctx) {
        validate("R4", "application/fhir+json", "application/fhir+xml", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode());
                    assertTrue(r.getHeader("Content-Type").startsWith("application/fhir+xml"));
                    // Spec 005 R13: the OperationOutcome body is unchanged; ownership is in the headers on every format.
                    assertEquals("system", r.getHeader("X-Validation-Context-Owner"));
                    assertEquals("true", r.getHeader("X-Validation-Context-Shareable"));
                    assertTrue(r.bodyAsString().startsWith("<OperationOutcome"), r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void fhirJsonAcceptReturnsOperationOutcomeJson(VertxTestContext ctx) {
        validate("R4", "application/fhir+xml", "application/fhir+json", XML_PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("OperationOutcome", r.bodyAsJsonObject().getString("resourceType"));
                    ctx.completeNow();
                })));
    }

    @Test
    void unacceptableAcceptIs406(VertxTestContext ctx) {
        validate("R4", "application/json", "text/plain", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 406, "not-acceptable");
                    ctx.completeNow();
                })));
    }

    @Test
    void unsupportedContentTypeIs415(VertxTestContext ctx) {
        validate("R4", "text/csv", "application/json", "a,b")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 415, "unsupported-media-type");
                    ctx.completeNow();
                })));
    }

    @Test
    void invalidResourceIs200WithValidFalse(VertxTestContext ctx) {
        validate("R4", "application/json", "application/json", INVALID_PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    assertFalse(r.bodyAsJsonObject().getBoolean("valid"));
                    assertTrue(r.bodyAsString().contains("invalid_gender"), "issue reported: " + r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedJsonIsInvalidResourceProblem(VertxTestContext ctx) {
        validate("R4", "application/json", "application/json", "{\"resourceType\":")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 400, "invalid-resource");
                    assertEquals("FATAL", p.getJsonArray("issues").getJsonObject(0).getString("severity"));
                    ctx.completeNow();
                })));
    }

    @Test
    void wellFormedJsonThatIsNotFhirIsInvalidResourceProblem(VertxTestContext ctx) {
        validate("R4", "application/json", "application/json", "{\"resourceType\":\"NoSuchType\"}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-resource");
                    ctx.completeNow();
                })));
    }

    @Test
    void disabledVersionIs400(VertxTestContext ctx) {
        validate("R6", "application/json", "application/json", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void uuidValidatorPathIsRejected(VertxTestContext ctx) {
        validate("2b6c6a3e-1f1e-4a51-9d55-0a1b2c3d4e5f", "application/json", "application/json", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void lowercaseVersionIsAccepted(VertxTestContext ctx) {
        validate("r4", "application/json", "application/json", PATIENT)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    ctx.completeNow();
                })));
    }

    @Test
    void stu3IsServed(VertxTestContext ctx) {
        validate("STU3", "application/json", "application/json", "{\"resourceType\":\"Patient\",\"active\":true}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    assertEquals("STU3", r.bodyAsJsonObject().getString("fhirVersion"));
                    ctx.completeNow();
                })));
    }

    @Test
    void bodyOverLimitIs413(VertxTestContext ctx) {
        String big = "{\"resourceType\":\"Patient\",\"id\":\"" + "x".repeat(1_200_000) + "\"}";
        validate("R4", "application/json", "application/json", big)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 413, "payload-too-large");
                    ctx.completeNow();
                })));
    }

    @Test
    void bundleOverEntryLimitIs413(VertxTestContext ctx) {
        validate("R4", "application/json", "application/json", nzi.fhir.validator.testsupport.TestPackages.patientBundle(6))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 413, "payload-too-large");
                    assertTrue(p.getString("detail").contains("limit is 5"), p.getString("detail"));
                    ctx.completeNow();
                })));
    }

    @Test
    void bundleWithinEntryLimitIsValidated(VertxTestContext ctx) {
        validate("R4", "application/json", "application/json", nzi.fhir.validator.testsupport.TestPackages.patientBundle(5))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    ctx.completeNow();
                })));
    }

    @Test
    void profileQueryParameterIsApplied(VertxTestContext ctx) {
        String base = "http://hl7.org/fhir/StructureDefinition/Patient";
        post("/R4/validate?profile=" + base)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertLegacyJson(r);
                    assertTrue(r.bodyAsJsonObject().getJsonArray("appliedProfiles").contains(base));
                    ctx.completeNow();
                })));
    }

    private static final String KNOWN = "http://hl7.org/fhir/StructureDefinition/Patient";
    private static final String TYPO = "http://example.org/StructureDefinition/typo";

    private Future<HttpResponse<Buffer>> validateWithProfiles(String profiles, String accept, String body) {
        HttpRequest<Buffer> req = post("/R4/validate").addQueryParam("profile", profiles)
                .putHeader("Content-Type", "application/json");
        if (accept != null) {
            req.putHeader("Accept", accept);
        }
        return req.sendBuffer(Buffer.buffer(body));
    }

    @Test
    void unknownProfileAmongKnownOnesIsRejectedWithItsUrl(VertxTestContext ctx) {
        validateWithProfiles(KNOWN + "," + TYPO, null, "{\"resourceType\":\"Patient\",\"active\":true}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 400, "unknown-profile");
                    assertEquals(new io.vertx.core.json.JsonArray().add(TYPO), p.getJsonArray("unresolvedProfiles"));
                    assertNull(p.getValue("valid"), "no validation result for an unresolved profile");
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownProfileIsProblemJsonEvenWhenOperationOutcomeIsAccepted(VertxTestContext ctx) {
        validateWithProfiles(TYPO, "application/fhir+xml", "{\"resourceType\":\"Patient\",\"active\":true}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "unknown-profile");
                    ctx.completeNow();
                })));
    }

    @Test
    void unparseableBodyIsReportedBeforeAnUnknownProfile(VertxTestContext ctx) {
        validateWithProfiles(TYPO, null, "{\"resourceType\":\"NotAResource\"}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-resource");
                    ctx.completeNow();
                })));
    }

    @Test
    void moreThanTenProfilesAreRejectedWithoutEchoingThem(VertxTestContext ctx) {
        String eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> TYPO + i)
                .collect(java.util.stream.Collectors.joining(","));
        validateWithProfiles(eleven, null, "{\"resourceType\":\"Patient\",\"active\":true}")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject p = assertProblem(r, 400, "invalid-request");
                    assertNull(p.getValue("unresolvedProfiles"));
                    assertFalse(r.bodyAsString().contains(TYPO), "limit errors must not echo the URLs");
                    ctx.completeNow();
                })));
    }

    @Test
    void registerProfileOperationIsGone(VertxTestContext ctx) {
        String json = "{\"resourceType\":\"StructureDefinition\",\"url\":\"" + TYPO + "\"}";
        post("/R4/register-profile").putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(json))
                .compose(anonymous -> {
                    ctx.verify(() -> assertProblem(anonymous, 404, "not-found"));
                    return AppHarness.asAdmin(post("/R4/register-profile"))
                            .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(json));
                })
                .onComplete(ctx.succeeding(admin -> ctx.verify(() -> {
                    assertProblem(admin, 404, "not-found");
                    String openapi = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/openapi.yaml"))
                            .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertFalse(openapi.contains("profileApiRegisterProfile"));
                    assertFalse(openapi.contains("register-profile"));
                    ctx.completeNow();
                })));
    }

    @Test
    void includeStoredIgSucceeds(VertxTestContext ctx) {
        app.bootstrap().services().igPackageService().registerIg(Fixtures.bytes(Fixtures.DK_CORE_IG))
                .compose(p -> AppHarness.asAdmin(post("/R4/validator")).sendJsonObject(new JsonObject()
                        .put("igPackageId", "hl7.fhir.dk.core").put("igPackageVersion", "3.4.0")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals("success", body.getString("status"));
                    assertTrue(body.getJsonArray("resolved").contains("hl7.fhir.dk.core#3.4.0"));
                    assertNotNull(body.getLong("revision"));
                    assertNotNull(body.getJsonArray("warnings"));
                    ctx.completeNow();
                })));
    }

    /** Spec 006 FR-011, FR-013, FR-017: a series includes a concrete version and the response echoes the request. */
    @Test
    void includeWithASeriesReportsTheConcreteKeyAndTheRequestedVersion(VertxTestContext ctx) {
        app.bootstrap().services().igPackageService()
                .registerIg(nzi.fhir.validator.testsupport.TestPackages.profilePackage("ct.inc", "1.2.3"))
                .compose(p -> app.bootstrap().services().configSync().syncCatalogNow())
                .compose(v -> AppHarness.asAdmin(post("/R4/validator").timeout(60_000)).sendJsonObject(new JsonObject()
                        .put("igPackageId", "ct.inc").put("igPackageVersion", "1.2.x")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals("ct.inc#1.2.3", body.getJsonObject("key").getString("igPackage"));
                    assertEquals("1.2.x", body.getString("requestedVersion"));
                    nzi.fhir.validator.testsupport.ContractAssertions.assertDeclaredBySchema(body, "IncludeResponse",
                            "requestedVersion");
                    ctx.completeNow();
                })));
    }

    /** Spec 006 FR-002: a malformed series on include is a client error. */
    @Test
    void includeWithAMalformedSeriesIs400(VertxTestContext ctx) {
        AppHarness.asAdmin(post("/R4/validator")).sendJsonObject(new JsonObject()
                        .put("igPackageId", "ct.inc").put("igPackageVersion", "1.x.3"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertEquals("Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)", problem.getString("detail"));
                    ctx.completeNow();
                })));
    }

    /** Spec 006 FR-018: the ig parameter needs an exact version; a series is 400 before anything is validated. */
    @Test
    void validateWithASeriesInTheIgParameterIs400(VertxTestContext ctx) {
        post("/R4/validate").addQueryParam("ig", "some.pkg#1.2.x")
                .putHeader("Content-Type", "application/fhir+json")
                .sendBuffer(Buffer.buffer(PATIENT))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertEquals("The ig parameter needs an exact version (for example the version reported when it was included), "
                            + "not a version series", problem.getString("detail"));
                    assertNull(r.getHeader("X-Validation-Context"));
                    ctx.completeNow();
                })));
    }

    @Test
    void includeOfUnstoredIgIs404(VertxTestContext ctx) {
        AppHarness.asAdmin(post("/R4/validator")).sendJsonObject(new JsonObject().put("igPackageId", "hl7.fhir.us.core").put("igPackageVersion", "3.1.1"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void includeWithoutPackageIdIs400(VertxTestContext ctx) {
        AppHarness.asAdmin(post("/R4/validator")).sendJsonObject(new JsonObject().put("igVersion", "3.1.1"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    /** Spec 004 contracts §2, §3: IncludeResponse carries the context key; DELETE answers ExcludeResponse or 404. */
    @Test
    void includeAndExcludeResponseShapes(VertxTestContext ctx) {
        app.bootstrap().services().igPackageService()
                .registerIg(nzi.fhir.validator.testsupport.TestPackages.npmPackage("contract.ig", "1.0.0", java.util.Map.of(),
                        java.util.List.of()), false)
                .compose(p -> AppHarness.asAdmin(post("/R4/validator"))
                        .sendJsonObject(new JsonObject().put("igPackageId", "contract.ig").put("igPackageVersion", "1.0.0")))
                .compose(included -> {
                    ctx.verify(() -> {
                        assertEquals(200, included.statusCode(), included.bodyAsString());
                        JsonObject body = included.bodyAsJsonObject();
                        assertEquals("contract.ig#1.0.0", body.getJsonObject("key").getString("igPackage"));
                        assertEquals("R4", body.getJsonObject("key").getString("fhirVersion"));
                        for (String field : java.util.List.of("status", "fhirVersion", "revision", "included", "resolved", "warnings",
                                "owner", "shareable")) {
                            assertTrue(body.containsKey(field), field);
                        }
                        assertEquals("system", body.getString("owner"), "the bootstrap key's organisation (spec 005 FR-006)");
                        assertEquals(Boolean.TRUE, body.getBoolean("shareable"), "shareable by default (FR-016)");
                    });
                    return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator").timeout(5_000))
                            .addQueryParam("ig", "contract.ig#1.0.0").send();
                })
                .compose(excluded -> {
                    ctx.verify(() -> {
                        assertEquals(200, excluded.statusCode(), excluded.bodyAsString());
                        JsonObject body = excluded.bodyAsJsonObject();
                        assertEquals("contract.ig#1.0.0", body.getString("excluded"));
                        for (String field : java.util.List.of("status", "fhirVersion", "revision", "included", "warnings")) {
                            assertTrue(body.containsKey(field), field);
                        }
                    });
                    return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator").timeout(5_000))
                            .addQueryParam("ig", "contract.ig#1.0.0").send();
                })
                .compose(again -> {
                    ctx.verify(() -> assertProblem(again, 404, "package-not-found"));
                    return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator").timeout(5_000)).send();
                })
                .onComplete(ctx.succeeding(missingIg -> ctx.verify(() -> {
                    assertEquals(400, missingIg.statusCode(), "ig is required on DELETE: " + missingIg.bodyAsString());
                    ctx.completeNow();
                })));
    }
}
