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
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.StubRegistry;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 US4 (T048): with access control disabled no endpoint needs a key, the {@code Authorization} header is
 * ignored, and each request runs as a fixed caller, so ownership, access rules and usage accounting work as before. The
 * admin network allowlist still applies.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class AclDisabledIT extends BaseTestContainer {
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    private AppHarness app;
    private StubRegistry registry;

    private Future<AppHarness> start(Vertx vertx, String adminCidrs) {
        resetSchema();
        registry = new StubRegistry().add("acl.reg", "1.0.0", TestPackages.profilePackage("acl.reg", "1.0.0"));
        return registry.start(vertx).compose(r -> {
            JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME,
                    POSTGRES_PASSWORD);
            config.put("fhir", new JsonObject().put("enabledVersions", "R4"));
            config.put("usage", new JsonObject().put("sink", "db").put("flushMs", 100));
            config.put("packages", new JsonObject().put("sources", registry.url()).put("registryEnabled", true)
                    .put("allowInsecureSources", true));
            config.getJsonObject("auth").put("globalDisabledAclSecurity", true);
            if (adminCidrs != null) {
                config.getJsonObject("auth").put("adminCidrs", adminCidrs);
            }
            return AppHarness.start(vertx, config, nzi.fhir.validator.core.metrics.ServiceMetrics.newRegistry());
        }).compose(a -> {
            app = a;
            return a.awaitReady(120_000).map(a);
        });
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> registry == null ? Future.succeededFuture() : registry.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private HttpRequest<Buffer> post(String path) {
        return app.client().post(app.port(), "localhost", path).timeout(60_000);
    }

    private Future<HttpResponse<Buffer>> validate(String query, String authorization) {
        HttpRequest<Buffer> r = post("/R4/validate" + query).putHeader("Content-Type", "application/fhir+json");
        if (authorization != null) {
            r.putHeader("Authorization", authorization);
        }
        return r.sendBuffer(Buffer.buffer(PATIENT));
    }

    private Future<List<String>> column(String sql) {
        return app.bootstrap().services().pool().query(sql).execute().map(rows -> {
            List<String> out = new ArrayList<>();
            rows.forEach(r -> out.add(String.valueOf(r.getValue(0))));
            return out;
        });
    }

    /** US4-1 to US4-6, FR-017 to FR-020, FR-023. */
    @Test
    void everyEndpointWorksWithoutAKeyAsItsFixedCaller(Vertx vertx, VertxTestContext ctx) {
        var services = new Object() {
            nzi.fhir.validator.ServiceRegistry get() {
                return app.bootstrap().services();
            }
        };
        start(vertx, null)
                .compose(a -> TestPackages.storePackage(services.get(), TestPackages.profilePackage("acl.pkg", "1.0.0")))
                // US4-1, US4-4, FR-019: an ADMIN operation without a header, owned by the system organisation.
                .compose(v -> post("/R4/validator").sendJsonObject(new JsonObject().put("igPackageId", "acl.pkg")))
                .compose(included -> {
                    ctx.verify(() -> {
                        assertEquals(200, included.statusCode(), included.bodyAsString());
                        assertEquals("system", included.bodyAsJsonObject().getString("owner"));
                    });
                    // US4-2: whatever the header holds, the request is never refused because of it.
                    return Future.all(List.of(validate("", "Bearer garbage"), validate("", "Basic abc"),
                            validate("", AppHarness.VALIDATOR_AUTH), validate("", null)));
                })
                .compose(validations -> {
                    ctx.verify(() -> validations.<HttpResponse<Buffer>>list().forEach(r ->
                            assertEquals(200, r.statusCode(), r.bodyAsString())));
                    // US4-5: a package read runs as the ADMIN fixed caller.
                    return app.client().get(app.port(), "localhost", "/igs/acl.pkg/1.0.0/dependencies").send();
                })
                .compose(deps -> {
                    ctx.verify(() -> assertEquals(200, deps.statusCode(), deps.bodyAsString()));
                    // FR-019: an upload and a registration are owned by the system organisation.
                    return post("/igs/upload").sendMultipartForm(io.vertx.ext.web.multipart.MultipartForm.create()
                            .binaryFileUpload("file", "acl.up.tgz", Buffer.buffer(TestPackages.profilePackage("acl.up", "1.0.0")),
                                    "application/gzip"));
                })
                .compose(upload -> {
                    ctx.verify(() -> assertEquals(200, upload.statusCode(), upload.bodyAsString()));
                    return post("/igs/register").sendJsonObject(new JsonObject().put("name", "acl.reg").put("version", "1.0.0"));
                })
                .compose(register -> {
                    ctx.verify(() -> assertEquals(200, register.statusCode(), register.bodyAsString()));
                    return column("SELECT ig_package_id || '|' || owner FROM fhir_validator_schema.fhir_implementation_guides "
                            + "WHERE ig_package_id IN ('acl.up', 'acl.reg') ORDER BY 1");
                })
                .compose(owners -> {
                    ctx.verify(() -> assertEquals(List.of("acl.reg|" + Constant.SYSTEM_ORG_ID, "acl.up|" + Constant.SYSTEM_ORG_ID),
                            owners));
                    // US4-3, FR-020: usage is charged to the trial validator and the trial organisation.
                    return Await.until(vertx, 10_000, "usage rows", () -> column(
                            "SELECT count(*) FROM fhir_validator_schema.validation_usage").map(n -> Long.parseLong(n.get(0)) >= 4));
                })
                .compose(v -> column("SELECT DISTINCT api_key_id || '|' || org_id FROM fhir_validator_schema.validation_usage"))
                .compose(usage -> {
                    ctx.verify(() -> assertEquals(List.of(Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "|" + Constant.TRIAL_ORG_ID),
                            usage));
                    return Future.all(app.client().get(app.port(), "localhost", "/health").send(),
                            app.client().get(app.port(), "localhost", "/metrics").send());
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    // US4-6, FR-023.
                    JsonObject health = cf.<HttpResponse<Buffer>>resultAt(0).bodyAsJsonObject();
                    assertEquals(new JsonObject().put("aclSecurity", "disabled").put("adminNetworkRestricted", false),
                            health.getJsonObject("security"));
                    String metrics = cf.<HttpResponse<Buffer>>resultAt(1).bodyAsString();
                    assertTrue(metrics.contains("fv_acl_security_disabled 1.0"), "gauge exported");
                    assertTrue(metrics.contains("fv_validator_contexts_inactive 0.0"), "gauge exported");
                    ctx.completeNow();
                })));
    }

    /** US4-8, FR-031: the trial validator sees shareable contexts only; a private one answers 404. */
    @Test
    void privateContextsStayPrivate(Vertx vertx, VertxTestContext ctx) {
        start(vertx, null)
                .compose(a -> {
                    var services = a.bootstrap().services();
                    return TestPackages.storePackage(services, TestPackages.profilePackage("acl.private", "1.0.0"))
                            .compose(v -> TestPackages.storePackage(services, TestPackages.profilePackage("acl.shared", "1.0.0")))
                            .compose(v -> services.igPackageService().include(Caller.system("system"), SupportedFhirVersion.R4,
                                    "acl.private", "1.0.0", Optional.of(false)))
                            .compose(v -> services.igPackageService().include(Caller.system("system"), SupportedFhirVersion.R4,
                                    "acl.shared", "1.0.0", Optional.of(true)));
                })
                .compose(v -> Future.all(validate("?ig=acl.private%231.0.0", null), validate("?ig=acl.shared%231.0.0", null)))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertProblem(cf.resultAt(0), 404, "package-not-found");
                    assertEquals(200, cf.<HttpResponse<Buffer>>resultAt(1).statusCode(), cf.<HttpResponse<Buffer>>resultAt(1).bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** US4-7, FR-030: with an allowlist, ADMIN operations from elsewhere are refused; validation isn't administrative. */
    @Test
    void theAdminNetworkAllowlistStillApplies(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "10.0.0.0/8")
                .compose(a -> Future.all(post("/R4/validator").sendJsonObject(new JsonObject().put("igPackageId", "x")),
                        validate("", null), app.client().get(app.port(), "localhost", "/health").send()))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(cf.resultAt(0), 403, "forbidden");
                    assertEquals("Administrative operations are not allowed from this network", problem.getString("detail"));
                    assertEquals(200, cf.<HttpResponse<Buffer>>resultAt(1).statusCode());
                    assertTrue(cf.<HttpResponse<Buffer>>resultAt(2).bodyAsJsonObject().getJsonObject("security")
                            .getBoolean("adminNetworkRestricted"));
                    ctx.completeNow();
                })));
    }
}
