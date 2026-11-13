package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 US1 and US2 (T017, T023): an instance serves only the versions in {@code FV_ENABLED_FHIR_VERSIONS}, and
 * refuses to add a validator context for a supported version it doesn't serve.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class EnabledVersionsIT extends BaseTestContainer {
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    private AppHarness app;

    private Future<AppHarness> start(Vertx vertx, String enabledVersions) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME,
                POSTGRES_PASSWORD);
        if (enabledVersions != null) {
            config.put("fhir", new JsonObject().put("enabledVersions", enabledVersions));
        }
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(120_000).map(a);
        });
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<JsonObject> json(String path) {
        return app.client().get(app.port(), "localhost", path).send().map(HttpResponse::bodyAsJsonObject);
    }

    private Future<HttpResponse<Buffer>> validate(String version) {
        return AppHarness.asValidator(app.client().post(app.port(), "localhost", "/" + version + "/validate"))
                .putHeader("Content-Type", "application/fhir+json").sendBuffer(Buffer.buffer(PATIENT));
    }

    private Future<HttpResponse<Buffer>> include(String version, String id) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/" + version + "/validator").timeout(60_000))
                .sendJsonObject(new JsonObject().put("igPackageId", id));
    }

    private Pool pool() {
        return app.bootstrap().services().pool();
    }

    private Future<List<String>> column(String sql) {
        return pool().query(sql).execute().map(rows -> {
            List<String> out = new java.util.ArrayList<>();
            rows.forEach(r -> out.add(String.valueOf(r.getValue(0))));
            return out;
        });
    }

    /** US1-1, FR-002: unset serves every supported version, as in 4.1. */
    @Test
    void unsetServesEveryVersion(Vertx vertx, VertxTestContext ctx) {
        start(vertx, null)
                .compose(a -> Future.all(json("/health/readiness"), json("/health")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    JsonObject readiness = cf.resultAt(0);
                    JsonObject health = cf.resultAt(1);
                    assertEquals(java.util.Set.of("STU3", "R4", "R4B", "R5"), readiness.getJsonObject("versions").fieldNames());
                    assertEquals(new JsonArray().add("STU3").add("R4").add("R4B").add("R5"),
                            health.getJsonObject("fhirVersions").getJsonArray("served"));
                    ctx.completeNow();
                })));
    }

    /** US1-2, US1-6, FR-004, FR-005: only R4 is loaded and served; R5 gets the existing 400. */
    @Test
    void onlyTheEnabledVersionIsLoadedAndServed(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "R4")
                .compose(a -> Future.all(json("/health/readiness"), json("/health"), validate("R5"), validate("R4")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    JsonObject readiness = cf.resultAt(0);
                    assertEquals("UP", readiness.getString("status"));
                    assertEquals(java.util.Set.of("R4"), readiness.getJsonObject("versions").fieldNames(),
                            "readiness covers only the served version");
                    JsonObject health = cf.resultAt(1);
                    assertEquals(new JsonArray().add("R4"), health.getJsonObject("fhirVersions").getJsonArray("served"));
                    JsonObject problem = assertProblem(cf.resultAt(2), 400, "unsupported-fhir-version");
                    assertEquals("FHIR version 'R5' is not supported. Supported versions: R4", problem.getString("detail"));
                    assertEquals(200, cf.<HttpResponse<Buffer>>resultAt(3).statusCode());
                    assertFalse(app.bootstrap().services().validatorRegistry().details().containsKey("R5"),
                            "no R5 base context is built");
                    ctx.completeNow();
                })));
    }

    /** US1-3, FR-001: trimmed, any case, duplicates ignored. */
    @Test
    void listIsTrimmedCaseInsensitiveAndDeduplicated(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "r4, R5 ,R4")
                .compose(a -> json("/health/readiness"))
                .onComplete(ctx.succeeding(readiness -> ctx.verify(() -> {
                    assertEquals(java.util.Set.of("R4", "R5"), readiness.getJsonObject("versions").fieldNames());
                    ctx.completeNow();
                })));
    }

    /** US2-1, FR-008, SC-003: R5 isn't served → 422, nothing stored, no revision bump. */
    @Test
    void includingAContextForADisabledVersionIsRefusedAndStoresNothing(Vertx vertx, VertxTestContext ctx) {
        byte[] r5Ig = TestPackages.igPackage("ev.r5", "1.0.0", List.of("5.0.0"), Map.of());
        String revision = "SELECT revision FROM fhir_validator_schema.fhir_validator_logs WHERE fhir_version = 'R5'";
        String rows = "SELECT count(*) FROM fhir_validator_schema.fhir_validator_snapshots WHERE fhir_version = 'R5'";
        List<String> before = new java.util.ArrayList<>();
        start(vertx, "R4")
                .compose(a -> TestPackages.storePackage(a.bootstrap().services(), r5Ig))
                .compose(v -> Future.all(column(revision), column(rows)))
                .compose(cf -> {
                    before.addAll(cf.<List<String>>resultAt(0));
                    before.addAll(cf.<List<String>>resultAt(1));
                    return include("R5", "ev.r5");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        JsonObject problem = assertProblem(r, 422, "fhir-version-disabled");
                        assertEquals("IG validator contexts for R5 can't be added: this service serves R4 only "
                                + "(FV_ENABLED_FHIR_VERSIONS)", problem.getString("detail"));
                    });
                    return Future.all(column(revision), column(rows));
                })
                .compose(cf -> {
                    ctx.verify(() -> assertEquals(before, List.of(cf.<List<String>>resultAt(0).get(0),
                            cf.<List<String>>resultAt(1).get(0)), "no revision bump and no row"));
                    return include("R9", "ev.r5");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(400, r.statusCode(), "an unknown version is still 400: " + r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** US2-2, FR-009: a served version works as before and the new context is active. */
    @Test
    void includingAContextForAnEnabledVersionWorksAndIsActive(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "R4")
                .compose(a -> TestPackages.storePackage(a.bootstrap().services(),
                        TestPackages.profilePackage("ev.r4", "1.0.0", TestPackages.requiredPatientElements(
                                "http://example.org/fhir/StructureDefinition/ev-r4", "birthDate"))))
                .compose(v -> include("R4", "ev.r4"))
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return column("SELECT is_active::text || '|' || coalesce(inactive_reason, '') FROM "
                            + "fhir_validator_schema.fhir_validator_snapshots WHERE ig_package = 'ev.r4#1.0.0'");
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(List.of("true|"), rows);
                    ctx.completeNow();
                })));
    }

    /** US2-3: unset, every supported version passes the version check (a missing package is 404, not 422). */
    @Test
    void unsetDoesNotRefuseAnyVersion(Vertx vertx, VertxTestContext ctx) {
        start(vertx, null)
                .compose(a -> include("R5", "ev.missing"))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }
}
