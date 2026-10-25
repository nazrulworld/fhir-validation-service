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
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 regression / SC-006: who may call what (FR-012).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class ApiKeyAuthIT extends BaseTestContainer {
    private static final String VALIDATE_KEY = "validate-key-0123456789abcdefghijklmnopq";
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    private AppHarness app;

    private Future<AppHarness> start(Vertx vertx, JsonObject authOverrides) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("auth").mergeIn(authOverrides);
        if (authOverrides.containsKey("bootstrapAdminKey") && authOverrides.getValue("bootstrapAdminKey") == null) {
            config.getJsonObject("auth").remove("bootstrapAdminKey");
        }
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private HttpRequest<Buffer> req(String method, String path, String key) {
        HttpRequest<Buffer> r = method.equals("GET") ? app.client().get(app.port(), "localhost", path)
                : app.client().post(app.port(), "localhost", path);
        r.timeout(5_000);
        if (key != null) {
            r.putHeader("Authorization", "Bearer " + key);
        }
        return r;
    }

    private Future<List<HttpResponse<Buffer>>> callAdminOperations(String key) {
        JsonObject profile = TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/auth", "gender");
        return Future.all(
                req("POST", "/igs/register", key).sendJsonObject(new JsonObject().put("name", "x")),
                req("POST", "/R4/include-ig", key).sendJsonObject(new JsonObject().put("igPackageId", "x")),
                req("POST", "/R4/register-profile", key).sendJsonObject(profile),
                req("POST", "/igs/upload", key).sendBuffer(Buffer.buffer("x"))
        ).map(cf -> cf.list());
    }

    private Future<HttpResponse<Buffer>> validate(String key) {
        return req("POST", "/R4/validate", key).putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT));
    }

    @Test
    void withoutAnyAdminKeyConfiguredAdminOperationsAreRefused(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().putNull("bootstrapAdminKey"))
                .compose(a -> callAdminOperations(AppHarness.ADMIN_KEY))
                .onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
                    responses.forEach(r -> assertProblem(r, 401, "unauthorized"));
                    ctx.completeNow();
                })));
    }

    @Test
    void adminOperationsNeedAnAdminKeyAndValidationIsAnonymousByDefault(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject())
                .compose(a -> callAdminOperations(null))
                .compose(noKey -> {
                    ctx.verify(() -> noKey.forEach(r -> {
                        assertProblem(r, 401, "unauthorized");
                        assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
                    }));
                    return callAdminOperations("wrong-key-0123456789abcdefghijklmnopqrstu");
                })
                .compose(wrongKey -> {
                    ctx.verify(() -> wrongKey.forEach(r -> assertProblem(r, 401, "unauthorized")));
                    return req("POST", "/R4/register-profile", AppHarness.ADMIN_KEY)
                            .sendJsonObject(TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/ok", "gender"));
                })
                .compose(adminOk -> {
                    ctx.verify(() -> assertEquals(200, adminOk.statusCode(), adminOk.bodyAsString()));
                    return app.bootstrap().services().apiKeys().save("validator", VALIDATE_KEY, ApiKeyRepository.Role.VALIDATE);
                })
                .compose(v -> req("POST", "/R4/register-profile", VALIDATE_KEY)
                        .sendJsonObject(TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/no", "gender")))
                .compose(forbidden -> {
                    ctx.verify(() -> assertProblem(forbidden, 403, "forbidden"));
                    return validate(null);
                })
                .compose(anonymous -> {
                    ctx.verify(() -> assertEquals(200, anonymous.statusCode(), anonymous.bodyAsString()));
                    return Future.all(req("GET", "/health", null).send(), req("GET", "/health/readiness", null).send(),
                            req("GET", "/health/liveness", null).send());
                })
                .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                    health.list().forEach(r -> assertEquals(200, ((HttpResponse<?>) r).statusCode()));
                    ctx.completeNow();
                })));
    }

    @Test
    void authenticatedValidationModeRequiresAKeyAndHonoursRevocation(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().put("anonymousValidate", false))
                .compose(a -> validate(null))
                .compose(anonymous -> {
                    ctx.verify(() -> assertProblem(anonymous, 401, "unauthorized"));
                    return app.bootstrap().services().apiKeys().save("validator", VALIDATE_KEY, ApiKeyRepository.Role.VALIDATE);
                })
                .compose(v -> validate(VALIDATE_KEY))
                .compose(withKey -> {
                    ctx.verify(() -> assertEquals(200, withKey.statusCode(), withKey.bodyAsString()));
                    return req("GET", "/igs/none/1.0.0/dependencies", null).send();
                })
                .compose(readAnonymous -> {
                    ctx.verify(() -> assertProblem(readAnonymous, 401, "unauthorized"));
                    return app.bootstrap().services().apiKeys().revoke("validator");
                })
                .compose(v -> Await.until(vertx, 5_000, "revoked key is refused",
                        () -> validate(VALIDATE_KEY).map(r -> r.statusCode() == 401)))
                .onComplete(ctx.succeedingThenComplete());
    }
}
