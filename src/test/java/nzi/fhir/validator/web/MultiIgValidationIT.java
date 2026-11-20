package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A_PROFILE;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B_PROFILE;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_C;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 012 US2 (FR-010 to FR-016, SC-001 to SC-003): validating against a two-IG context over HTTP.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class MultiIgValidationIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String A1 = MULTI_A + "#1.0.0";
    private static final String B2 = MULTI_B + "#2.0.0";
    private static final String C3 = MULTI_C + "#3.0.0";
    private static final String KEY = A1 + "|" + B2;
    /** Private to organisation "owner": a context with it is invisible to others. */
    private static final String PRIVATE_KEY = C3 + "|test.multi.x#1.0.0";
    /** A Patient claiming both IGs' profiles and missing what each requires (birthDate, gender). */
    private static final String PATIENT = new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true)
            .put("meta", new JsonObject().put("profile", new JsonArray().add(MULTI_A_PROFILE).add(MULTI_B_PROFILE))).encode();

    private AppHarness app;
    private String otherKey;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("usage", new JsonObject().put("sink", "db").put("flushMs", 100));
        AppHarness.start(vertx, config)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .compose(v -> store(TestPackages.multiA(), TestPackages.multiB(), TestPackages.multiC(), TestPackages.conflictX("1.0.0")))
                .compose(v -> services().configSync().syncCatalogNow())
                .compose(v -> TestPackages.includePair(services(), R4, MULTI_A, "1.0.0", MULTI_B, "2.0.0"))
                .compose(v -> TestPackages.includePair(services(), R4, MULTI_C, "3.0.0", "test.multi.x", "1.0.0"))
                .compose(v -> {
                    Pool pool = services().pool();
                    // FR-014: a context whose IG is private to another organisation, and a key of that organisation.
                    return TestPackages.createOrganisation(pool, "owner")
                            .compose(owner -> TestPackages.setPackageAccess(pool, C3, owner, false)
                                    .compose(x -> TestPackages.createKey(pool, owner, "owner-key", "VALIDATE")))
                            .compose(key -> {
                                otherKey = key;
                                return services().configSync().syncCatalogNow();
                            });
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    private Future<Void> store(byte[]... packages) {
        Future<Void> f = Future.succeededFuture();
        for (byte[] p : packages) {
            f = f.compose(v -> TestPackages.storePackage(services(), p));
        }
        return f;
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private ServiceRegistry services() {
        return app.bootstrap().services();
    }

    /** {@code ig} is put into the URL as given, so a test controls the encoding of '#' and '|'. */
    private HttpRequest<Buffer> validate(String rawIgQuery) {
        return AppHarness.asValidator(app.client().post(app.port(), "localhost", "/R4/validate?ig=" + rawIgQuery))
                .timeout(60_000).putHeader("Content-Type", "application/fhir+json");
    }

    private static boolean mentions(HttpResponse<Buffer> r, String text) {
        return r.bodyAsJsonObject().getJsonArray("messages").stream()
                .map(m -> ((JsonObject) m).getString("message") + " " + ((JsonObject) m).getString("location"))
                .anyMatch(m -> m.contains(text));
    }

    private static String enc(String a, String b) {
        return a.replace("#", "%23") + "%7C" + b.replace("#", "%23");
    }

    @Test
    void bothIgsApplyInEitherOrder(VertxTestContext ctx) {
        ValidatorSnapshot before = services().validatorRegistry().current(SnapshotKey.of(R4, KEY));
        Future.all(validate(enc(A1, B2)).sendBuffer(Buffer.buffer(PATIENT)),
                        validate(enc(B2, A1)).sendBuffer(Buffer.buffer(PATIENT)),
                        validate(MULTI_A + "%231.0.0|" + MULTI_B + "%232.0.0").sendBuffer(Buffer.buffer(PATIENT)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < 3; i++) {
                        HttpResponse<Buffer> r = all.resultAt(i);
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        // SC-002: the same context, reported by its key, whatever the order or encoding.
                        assertEquals(KEY, r.getHeader("X-Validation-Context"));
                        assertEquals("system", r.getHeader("X-Validation-Context-Owner"));
                        assertEquals("true", r.getHeader("X-Validation-Context-Shareable"));
                        // SC-001: both IGs' profiles applied, no unknown profile.
                        assertTrue(mentions(r, "birthDate"), r.bodyAsString());
                        assertTrue(mentions(r, "gender"), r.bodyAsString());
                        assertEquals(List.of(MULTI_A_PROFILE, MULTI_B_PROFILE).stream().sorted().toList(),
                                r.bodyAsJsonObject().getJsonArray("appliedProfiles").getList().stream().map(Object::toString).sorted().toList());
                    }
                    // SC-003: served by the context built at include time, never rebuilt for a request.
                    assertSame(before, services().validatorRegistry().current(SnapshotKey.of(R4, KEY)));
                    ctx.completeNow();
                })));
    }

    @Test
    void profileParameterAcceptsProfilesFromBothIgs(VertxTestContext ctx) {
        String bare = TestPackages.minimalPatient();
        validate(enc(A1, B2) + "&profile=" + MULTI_A_PROFILE + "," + MULTI_B_PROFILE).sendBuffer(Buffer.buffer(bare))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertTrue(mentions(r, "birthDate") && mentions(r, "gender"), r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void anUnknownProfileNamesBothIgs(VertxTestContext ctx) {
        validate(enc(B2, A1) + "&profile=http://example.org/fhir/StructureDefinition/nowhere").sendBuffer(Buffer.buffer(PATIENT))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "unknown-profile");
                    assertTrue(problem.getString("detail").contains("IG packages " + A1 + " and " + B2), problem.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void aPairThatIsNotIncludedIs400(VertxTestContext ctx) {
        // Both stored and shareable, but not included together (C3 is private to another organisation here).
        validate(enc(A1, "test.multi.x#1.0.0")).sendBuffer(Buffer.buffer(PATIENT))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertTrue(problem.getString("detail").contains("not included for R4"), problem.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedPairsAre400(VertxTestContext ctx) {
        Future.all(validate(MULTI_A + "%7C" + MULTI_B.replace("#", "%23") + "%232.0.0").sendBuffer(Buffer.buffer(PATIENT)),
                        validate(enc(A1, MULTI_A + "#2.0.0")).sendBuffer(Buffer.buffer(PATIENT)),
                        validate(enc(A1, B2) + "%7C" + C3.replace("#", "%23")).sendBuffer(Buffer.buffer(PATIENT)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    for (int i = 0; i < 3; i++) {
                        assertProblem(all.resultAt(i), 400, "invalid-request");
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void perRequestTerminologyWorksAsForASingleIg(VertxTestContext ctx) {
        validate(enc(A1, B2) + "&txMode=none").sendBuffer(Buffer.buffer(PATIENT))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("none", r.getHeader("X-Tx-Mode"));
                    assertEquals("request", r.getHeader("X-Tx-Source"));
                    ctx.completeNow();
                })));
    }

    /** FR-014: a member or dependency the caller may not use is answered as not stored, never naming which. */
    @Test
    void accessFollowsEveryMember(VertxTestContext ctx) {
        String request = enc(C3, "test.multi.x#1.0.0");
        Future.all(
                        AppHarness.as(app.client().post(app.port(), "localhost", "/R4/validate?ig=" + request), otherKey)
                                .timeout(60_000).putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(PATIENT)),
                        validate(request).sendBuffer(Buffer.buffer(PATIENT)),
                        validate(enc("test.multi.x#1.0.0", C3)).sendBuffer(Buffer.buffer(PATIENT)))
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    HttpResponse<Buffer> owner = all.resultAt(0);
                    assertEquals(200, owner.statusCode(), "the owner of the private IG may use the context: " + owner.bodyAsString());
                    assertEquals(PRIVATE_KEY, owner.getHeader("X-Validation-Context"));
                    JsonObject first = assertProblem(all.resultAt(1), 404, "package-not-found");
                    JsonObject second = assertProblem(all.resultAt(2), 404, "package-not-found");
                    assertFalse(first.getString("detail").contains("private"), first.encode());
                    assertEquals(first.getString("detail").replace(request.replace("%23", "#").replace("%7C", "|"), "X"),
                            second.getString("detail").replace(("test.multi.x#1.0.0|" + C3), "X"),
                            "the same answer whichever member is hidden");
                    ctx.completeNow();
                })));
    }

    @Test
    void aDependencyTheCallerMayNotUseIs424(VertxTestContext ctx) {
        Pool pool = services().pool();
        UUID[] owner = new UUID[1];
        TestPackages.createOrganisation(pool, "depowner")
                .compose(id -> {
                    owner[0] = id;
                    // A fresh pair whose dependency becomes private to another organisation after the include.
                    return TestPackages.storePackage(services(), TestPackages.npmPackage("test.multi.dep", "1.0.0", java.util.Map.of(), List.of()))
                            .compose(v -> TestPackages.storePackage(services(), TestPackages.npmPackage("test.multi.uses", "1.0.0",
                                    java.util.Map.of("test.multi.dep", "1.0.0"), List.of())))
                            .compose(v -> services().configSync().syncCatalogNow())
                            .compose(v -> TestPackages.includePair(services(), R4, "test.multi.uses", "1.0.0", MULTI_A, "1.0.0"))
                            .compose(v -> TestPackages.setPackageAccess(pool, "test.multi.dep#1.0.0", id, false))
                            .compose(v -> services().configSync().syncCatalogNow());
                })
                .compose(v -> validate(enc("test.multi.uses#1.0.0", A1)).sendBuffer(Buffer.buffer(PATIENT)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 424, "dependency-not-found");
                    assertFalse(problem.getString("detail").contains("test.multi.dep"), problem.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void usageRecordsTheKey(Vertx vertx, VertxTestContext ctx) {
        Pool pool = services().pool();
        validate(enc(B2, A1)).putHeader("X-Request-Id", "multi-ig-usage").sendBuffer(Buffer.buffer(PATIENT))
                .compose(r -> Await.until(vertx, 10_000, "usage row", () -> pool.preparedQuery(
                                "SELECT count(*) FROM fhir_validator_schema.validation_usage WHERE request_id = $1")
                        .execute(Tuple.of("multi-ig-usage")).map(rows -> rows.iterator().next().getLong(0) > 0)))
                .compose(ms -> pool.preparedQuery("SELECT ig FROM fhir_validator_schema.validation_usage WHERE request_id = $1")
                        .execute(Tuple.of("multi-ig-usage")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(KEY, rows.iterator().next().getString(0));
                    ctx.completeNow();
                })));
    }
}
