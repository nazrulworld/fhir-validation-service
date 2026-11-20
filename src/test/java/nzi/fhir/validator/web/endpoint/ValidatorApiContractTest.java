package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertDeclaredBySchema;
import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_A;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_B;
import static nzi.fhir.validator.testsupport.TestPackages.MULTI_C;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP contract of {@code /{version}/validator} for two-IG contexts (spec 012 contracts §1, §3, §4; tasks T016, T036a).
 * The tests share one application and run in order: include, change, health, exclude.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class ValidatorApiContractTest extends BaseTestContainer {
    private static final String A1 = MULTI_A + "#1.0.0";
    private static final String B2 = MULTI_B + "#2.0.0";
    private static final String KEY = A1 + "|" + B2;
    private static final String REVERSED_PARAM = MULTI_B + "%232.0.0%7C" + MULTI_A + "%231.0.0";

    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                        POSTGRES_USERNAME, POSTGRES_PASSWORD))
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .compose(v -> TestPackages.storePackage(services(), TestPackages.multiA()))
                .compose(v -> TestPackages.storePackage(services(), TestPackages.multiB()))
                .compose(v -> TestPackages.storePackage(services(), TestPackages.multiC()))
                .compose(v -> services().configSync().syncCatalogNow())
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private ServiceRegistry services() {
        return app.bootstrap().services();
    }

    private Future<HttpResponse<Buffer>> include(JsonObject body) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator")).timeout(60_000)
                .sendJsonObject(body);
    }

    private static JsonObject entry(String id, String version) {
        JsonObject e = new JsonObject().put("igPackageId", id);
        return version == null ? e : e.put("igPackageVersion", version);
    }

    @Test
    @Order(1)
    void malformedIgPackagesAre400(VertxTestContext ctx) {
        include(new JsonObject().put("igPackages", new JsonArray()))
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "invalid-request"));
                    return include(new JsonObject().put("igPackages", new JsonArray()
                            .add(entry(MULTI_A, null)).add(entry(MULTI_B, null)).add(entry(MULTI_C, null))));
                })
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "invalid-request"));
                    return include(new JsonObject().put("igPackageId", MULTI_A)
                            .put("igPackages", new JsonArray().add(entry(MULTI_B, null))));
                })
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "invalid-request"));
                    return include(new JsonObject().put("igPackages", new JsonArray()
                            .add(entry(MULTI_A, "1.0.0")).add(entry(MULTI_A, "1.0.0"))));
                })
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "invalid-request"));
                    return include(new JsonObject());
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(2)
    void includeTwoIgs(VertxTestContext ctx) {
        include(new JsonObject().put("igPackages", new JsonArray().add(entry(MULTI_A, "1.0.0")).add(entry(MULTI_B, null))))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertDeclaredBySchema(body, "IncludeResponse", "igPackages", "aliases");
                    assertEquals(KEY, body.getJsonObject("key").getString("igPackage"));
                    assertNull(body.getString("requestedVersion"), "a two-IG include has no top-level requestedVersion");
                    JsonArray members = body.getJsonArray("igPackages");
                    assertEquals("1.0.0", members.getJsonObject(0).getString("requestedVersion"));
                    assertEquals("latest", members.getJsonObject(1).getString("requestedVersion"));
                    assertEquals("2.0.0", members.getJsonObject(1).getString("igPackageVersion"));
                    assertEquals(new JsonArray().add(B2 + "|" + A1).add(A1), body.getJsonArray("aliases"));
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(3)
    void oneEntryBehavesLikeIgPackageId(VertxTestContext ctx) {
        include(new JsonObject().put("igPackages", new JsonArray().add(entry(MULTI_C, "3.0.0"))))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals(MULTI_C + "#3.0.0", body.getJsonObject("key").getString("igPackage"));
                    assertEquals("3.0.0", body.getString("requestedVersion"));
                    assertEquals(new JsonArray(), body.getJsonArray("aliases"));
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(4)
    void aliasConflictIsProblemJson(VertxTestContext ctx) {
        include(new JsonObject().put("igPackages", new JsonArray().add(entry(MULTI_A, "1.0.0")).add(entry(MULTI_C, "3.0.0"))))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 409, "alias-conflict");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(5)
    void healthListsMembersAndAliases(VertxTestContext ctx) {
        app.client().get(app.port(), "localhost", "/health").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonArray contexts = r.bodyAsJsonObject().getJsonObject("readiness").getJsonObject("versions")
                            .getJsonObject("R4").getJsonArray("contexts");
                    JsonObject pair = contexts.stream().map(JsonObject.class::cast)
                            .filter(c -> KEY.equals(c.getString("key"))).findFirst().orElseThrow(() -> new AssertionError(contexts.encode()));
                    assertEquals(new JsonArray().add(A1).add(B2), pair.getJsonArray("igPackages"));
                    assertEquals(new JsonArray().add(B2 + "|" + A1).add(A1), pair.getJsonArray("aliases"));
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(6)
    void patchByReversedPair(VertxTestContext ctx) {
        AppHarness.asAdmin(app.client().patch(app.port(), "localhost", "/R4/validator?ig=" + REVERSED_PARAM)).timeout(30_000)
                .sendJsonObject(new JsonObject().put("shareable", false))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals(KEY, r.bodyAsJsonObject().getString("igPackage"));
                        assertFalse(r.bodyAsJsonObject().getBoolean("shareable"));
                    });
                    // A single name#version never addresses a two-IG context (FR-017).
                    return AppHarness.asAdmin(app.client().patch(app.port(), "localhost", "/R4/validator?ig=" + MULTI_A + "%231.0.0"))
                            .timeout(30_000).sendJsonObject(new JsonObject().put("shareable", true));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(7)
    void deleteByReversedPair(VertxTestContext ctx) {
        AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator?ig=" + MULTI_A + "%231.0.0"))
                .timeout(30_000).send()
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 404, "package-not-found"));
                    return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator?ig=" + REVERSED_PARAM))
                            .timeout(30_000).send();
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertDeclaredBySchema(r.bodyAsJsonObject(), "ExcludeResponse");
                    assertEquals(KEY, r.bodyAsJsonObject().getString("excluded"));
                    assertFalse(r.bodyAsJsonObject().getJsonArray("included").contains(KEY));
                    ctx.completeNow();
                })));
    }
}
