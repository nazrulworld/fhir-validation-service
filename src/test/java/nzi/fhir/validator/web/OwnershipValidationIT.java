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
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.endpoint.ValidationApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 005 US1 over HTTP (scenarios 1, 2, 4-8; FR-010 to FR-013, FR-020, SC-002): validation and the package read
 * endpoints use only what the caller's organisation owns or what is shareable, and a refusal looks exactly like "not
 * stored".
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class OwnershipValidationIT extends BaseTestContainer {
    private static final String P = "http://example.org/fhir/StructureDefinition/owned";

    private AppHarness app;
    private String keyA;
    private String keyB;

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
                .compose(v -> setUpData())
                .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Organisations {@code orga} and {@code orgb}, with:
     * <ul>
     *     <li>{@code own.a#1.0.0}: private to A;</li>
     *     <li>{@code own.b#1.0.0}: shareable, owned by B;</li>
     *     <li>{@code own.p#1.0.0} shareable and {@code own.p#2.0.0} private, both owned by A;</li>
     *     <li>{@code own.r5#1.0.0}: an R5 package private to A;</li>
     *     <li>{@code own.user#1.0.0}: shareable, owned by A, depending on {@code own.dep#1.0.0}, private to B.</li>
     *     <li>{@code own.fuser#1.0.0}: shareable, owned by A, depending on {@code own.fdep#1.0.x}; {@code own.fdep#1.0.3}
     *     is shareable and {@code own.fdep#1.0.5} private, both owned by B (spec 006 FR-024).</li>
     * </ul>
     */
    private Future<Void> setUpData() {
        ServiceRegistry services = app.bootstrap().services();
        Pool pool = services.pool();
        java.util.concurrent.atomic.AtomicReference<UUID> orgA = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<UUID> orgB = new java.util.concurrent.atomic.AtomicReference<>();
        return Future.all(TestPackages.createOrganisation(pool, "orga"), TestPackages.createOrganisation(pool, "orgb"))
                .compose(cf -> {
                    UUID a = cf.resultAt(0);
                    UUID b = cf.resultAt(1);
                    orgA.set(a);
                    orgB.set(b);
                    return TestPackages.createKey(pool, a, "orga-val", "VALIDATE")
                            .compose(k -> {
                                keyA = k;
                                return TestPackages.createKey(pool, b, "orgb-val", "VALIDATE");
                            })
                            .compose(k -> {
                                keyB = k;
                                return store("own.a", "1.0.0", a, false);
                            })
                            .compose(x -> store("own.b", "1.0.0", b, true))
                            .compose(x -> store("own.p", "1.0.0", a, true))
                            .compose(x -> store("own.p", "2.0.0", a, false))
                            .compose(x -> TestPackages.storePackage(services, TestPackages.igPackage("own.r5", "1.0.0",
                                    List.of("5.0.0"), Map.of())))
                            .compose(x -> TestPackages.setPackageAccess(pool, "own.r5#1.0.0", a, false))
                            .compose(x -> store("own.dep", "1.0.0", b, false))
                            .compose(x -> TestPackages.storePackage(services, TestPackages.igPackage("own.user", "1.0.0",
                                    List.of("4.0.1"), Map.of("own.dep", "1.0.0"), TestPackages.requiredPatientElements(P, "gender"))))
                            .compose(x -> TestPackages.setPackageAccess(pool, "own.user#1.0.0", a, true))
                            .compose(x -> store("own.fdep", "1.0.3", b, true))
                            .compose(x -> store("own.fdep", "1.0.5", b, false))
                            .compose(x -> TestPackages.storePackage(services, TestPackages.igPackage("own.fuser", "1.0.0",
                                    List.of("4.0.1"), Map.of("own.fdep", "1.0.x"), TestPackages.requiredPatientElements(P, "gender"))))
                            .compose(x -> TestPackages.setPackageAccess(pool, "own.fuser#1.0.0", a, true));
                })
                // The local store listener refreshes this instance's catalog; the access updates arrive by notification.
                .compose(v -> services.configSync().syncCatalogNow())
                // Spec 007 FR-021: only included contexts can be named in ig. Each is included by an organisation that
                // may use all its packages, with the sharing the tests expect.
                .compose(v -> include(orgA.get(), "orga", "own.a", "1.0.0", false))
                .compose(v -> include(orgB.get(), "orgb", "own.b", "1.0.0", true))
                .compose(v -> include(orgA.get(), "orga", "own.p", "1.0.0", true))
                .compose(v -> include(orgA.get(), "orga", "own.p", "2.0.0", false))
                .compose(v -> include(orgB.get(), "orgb", "own.user", "1.0.0", true))
                .compose(v -> include(orgA.get(), "orga", "own.fuser", "1.0.0", true));
    }

    private Future<Void> include(UUID org, String shortName, String name, String version, boolean shareable) {
        return app.bootstrap().services().igPackageService().include(
                nzi.fhir.validator.core.security.Caller.of(org, shortName, nzi.fhir.validator.core.security.ApiKeyRepository.Role.ADMIN),
                nzi.fhir.validator.core.enums.SupportedFhirVersion.R4, name, version, java.util.Optional.of(shareable)).mapEmpty();
    }

    private Future<Void> store(String name, String version, UUID owner, boolean shareable) {
        return TestPackages.storePackage(app.bootstrap().services(), TestPackages.profilePackage(name, version,
                        TestPackages.requiredPatientElements(P, "birthDate")))
                .compose(v -> TestPackages.setPackageAccess(app.bootstrap().services().pool(), name + "#" + version, owner, shareable));
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<HttpResponse<Buffer>> validate(String version, String ig, String key) {
        HttpRequest<Buffer> req = app.client().post(app.port(), "localhost", "/" + version + "/validate").timeout(30_000)
                .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", "application/json");
        if (ig != null) {
            req.addQueryParam("ig", ig);
        }
        if (key != null) {
            AppHarness.as(req, key);
        }
        return req.sendBuffer(Buffer.buffer(TestPackages.minimalPatient()));
    }

    private Future<HttpResponse<Buffer>> get(String path, String key) {
        HttpRequest<Buffer> req = app.client().get(app.port(), "localhost", path).timeout(30_000);
        return (key == null ? req : AppHarness.as(req, key)).send();
    }

    /** A problem body without its per-request fields and with {@code label} replaced, for comparing two refusals. */
    private static JsonObject normalised(HttpResponse<Buffer> r, String label) {
        JsonObject body = r.bodyAsJsonObject().copy();
        body.remove("correlationId");
        body.remove("instance");
        body.put("detail", body.getString("detail").replace(label, "<ig>"));
        return body;
    }

    @Test
    void ownerValidatesAndSeesTheOwner(VertxTestContext ctx) {
        validate("R4", "own.a#1.0.0", keyA).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            assertEquals(200, r.statusCode(), r.bodyAsString());
            assertEquals("own.a#1.0.0", r.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
            assertEquals("orga", r.getHeader(ValidationApi.VALIDATION_CONTEXT_OWNER_HEADER));
            assertEquals("false", r.getHeader(ValidationApi.VALIDATION_CONTEXT_SHAREABLE_HEADER));
            JsonObject body = r.bodyAsJsonObject();
            assertEquals("orga", body.getString("igOwner"));
            assertFalse(body.getBoolean("igShareable"));
            ctx.completeNow();
        })));
    }

    @Test
    void otherOrganisationAndAnonymousGetExactlyNotStored(VertxTestContext ctx) {
        Future.all(validate("R4", "own.a#1.0.0", keyB), validate("R4", "own.a#1.0.0", null),
                        validate("R4", "does.not.exist#1.0.0", keyB))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<Buffer> other = cf.resultAt(0);
                    HttpResponse<Buffer> anonymous = cf.resultAt(1);
                    HttpResponse<Buffer> missing = cf.resultAt(2);
                    assertProblem(other, 404, "package-not-found");
                    assertProblem(anonymous, 404, "package-not-found");
                    assertEquals(normalised(missing, "does.not.exist#1.0.0"), normalised(other, "own.a#1.0.0"),
                            "a refusal is indistinguishable from not stored (SC-002)");
                    assertEquals(normalised(missing, "does.not.exist#1.0.0"), normalised(anonymous, "own.a#1.0.0"));
                    assertEquals(new java.util.TreeSet<>(missing.headers().names()), new java.util.TreeSet<>(other.headers().names()),
                            "the same response headers");
                    ctx.completeNow();
                })));
    }

    @Test
    void sharedPackageOfAnotherOrganisationIsUsable(VertxTestContext ctx) {
        Future.all(validate("R4", "own.b#1.0.0", keyA), validate("R4", "own.b#1.0.0", null))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    for (int i = 0; i < 2; i++) {
                        HttpResponse<Buffer> r = cf.resultAt(i);
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals("orgb", r.getHeader(ValidationApi.VALIDATION_CONTEXT_OWNER_HEADER));
                        assertEquals("true", r.getHeader(ValidationApi.VALIDATION_CONTEXT_SHAREABLE_HEADER));
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void versionlessResolvesToTheHighestVisibleVersion(VertxTestContext ctx) {
        Future.all(validate("R4", "own.p", keyB), validate("R4", "own.p", keyA), validate("R4", "own.p#latest", null))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<Buffer> b = cf.resultAt(0);
                    HttpResponse<Buffer> a = cf.resultAt(1);
                    HttpResponse<Buffer> anonymous = cf.resultAt(2);
                    assertEquals(200, b.statusCode(), b.bodyAsString());
                    assertEquals("own.p#1.0.0", b.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
                    assertEquals("own.p#2.0.0", a.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
                    assertEquals("own.p#1.0.0", anonymous.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
                    ctx.completeNow();
                })));
    }

    @Test
    void anInvisiblePackageForAnotherFhirVersionIs404Not400(VertxTestContext ctx) {
        Future.all(validate("R4", "own.r5#1.0.0", keyB), validate("R4", "own.r5#1.0.0", keyA))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertProblem(cf.resultAt(0), 404, "package-not-found");
                    assertProblem(cf.resultAt(1), 400, "invalid-request");
                    ctx.completeNow();
                })));
    }

    @Test
    void aPrivateDependencyIs424WithoutItsName(VertxTestContext ctx) {
        Future.all(validate("R4", "own.user#1.0.0", keyA), validate("R4", "own.user#1.0.0", keyB))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<Buffer> refused = cf.resultAt(0);
                    HttpResponse<Buffer> owner = cf.resultAt(1);
                    JsonObject problem = assertProblem(refused, 424, "dependency-not-found");
                    assertFalse(refused.bodyAsString().contains("own.dep"), refused.bodyAsString());
                    assertTrue(problem.getString("detail").contains("not available"));
                    assertEquals(200, owner.statusCode(), "the dependency's owner may use it: " + owner.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void withoutIgTheBaseContextIsSystemOwned(VertxTestContext ctx) {
        validate("R4", null, keyB).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            assertEquals(200, r.statusCode(), r.bodyAsString());
            assertEquals("base", r.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
            assertEquals("system", r.getHeader(ValidationApi.VALIDATION_CONTEXT_OWNER_HEADER));
            assertEquals("true", r.getHeader(ValidationApi.VALIDATION_CONTEXT_SHAREABLE_HEADER));
            assertEquals("system", r.bodyAsJsonObject().getString("igOwner"));
            ctx.completeNow();
        })));
    }

    @Test
    void readEndpointsHidePrivatePackages(VertxTestContext ctx) {
        Future.all(List.of(get("/igs/own.a/1.0.0/dependencies", keyB), get("/igs/own.a/1.0.0/conformance", keyB),
                        get("/igs/own.a/1.0.0/dependencies", null), get("/igs/own.a/1.0.0/dependencies", keyA),
                        get("/igs/own.a/1.0.0/conformance", keyA), get("/igs/none/1.0.0/dependencies", keyB)))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertProblem(cf.resultAt(0), 404, "package-not-found");
                    assertProblem(cf.resultAt(1), 404, "package-not-found");
                    assertProblem(cf.resultAt(2), 404, "package-not-found");
                    assertEquals(200, ((HttpResponse<?>) cf.resultAt(3)).statusCode());
                    assertEquals(200, ((HttpResponse<?>) cf.resultAt(4)).statusCode());
                    assertEquals(normalised(cf.resultAt(5), "none#1.0.0"), normalised(cf.resultAt(0), "own.a#1.0.0"));
                    ctx.completeNow();
                })));
    }

    @Test
    void refusalsAreCountedAsPackageNotFoundOnly(VertxTestContext ctx) {
        validate("R4", "own.a#1.0.0", keyB)
                .compose(r -> validate("R4", "own.user#1.0.0", keyA))
                .compose(r -> get("/metrics", null))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    String metrics = r.bodyAsString();
                    assertTrue(metrics.contains("outcome=\"package_not_found\""), "refusals are counted as package_not_found");
                    assertFalse(metrics.contains("access"), "no metric reveals access decisions");
                    assertFalse(metrics.contains("own.a") || metrics.contains("orga") || metrics.contains("own.dep"),
                            "no metric names private content or organisations");
                    ctx.completeNow();
                })));
    }

    /**
     * Spec 006 FR-024: an included context resolves a floating dependency as its owner, so another organisation's
     * private highest match is never chosen and the request isn't refused with 424.
     */
    @Test
    void aFloatingDependencySkipsAPrivateVersion(VertxTestContext ctx) {
        Future.all(validate("R4", "own.fuser#1.0.0", keyA), validate("R4", "own.fuser#1.0.0", keyB))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<Buffer> a = cf.resultAt(0);
                    HttpResponse<Buffer> b = cf.resultAt(1);
                    assertEquals(200, a.statusCode(), a.bodyAsString());
                    assertEquals(200, b.statusCode(), b.bodyAsString());
                    assertEquals("own.fuser#1.0.0", a.getHeader(ValidationApi.VALIDATION_CONTEXT_HEADER));
                    java.util.List<String> built = app.bootstrap().services().validatorRegistry().peekResolvedPackages(
                            nzi.fhir.validator.core.validator.SnapshotKey.of(nzi.fhir.validator.core.enums.SupportedFhirVersion.R4,
                                    "own.fuser#1.0.0")).orElseThrow();
                    assertTrue(built.contains("own.fdep#1.0.3") && !built.contains("own.fdep#1.0.5"), built.toString());
                    ctx.completeNow();
                })));
    }
}
