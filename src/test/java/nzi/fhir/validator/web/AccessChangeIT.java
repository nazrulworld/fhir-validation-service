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
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 005 US2 scenarios 8-9 and FR-019 over HTTP: both PATCH operations, their check order (404 → 403 → 400), and
 * propagation to a second instance without a rebuild.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class AccessChangeIT extends BaseTestContainer {
    private static final String PKG = "acc.pkg#1.0.0";
    private static final String PRIV = "acc.priv#1.0.0";

    private AppHarness app;
    private Vertx vertxB;
    private CoreHarness instanceB;
    private UUID orgA;
    private String adminA;
    private String adminB;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        vertxB = Vertx.vertx();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        AppHarness.start(vertx, config)
                .compose(a -> {
                    app = a;
                    return a.awaitReady(90_000);
                })
                .compose(v -> {
                    Pool pool = app.bootstrap().services().pool();
                    return Future.all(TestPackages.createOrganisation(pool, "orga"), TestPackages.createOrganisation(pool, "orgb"),
                            TestPackages.createOrganisation(pool, "orgc"));
                })
                .compose(cf -> {
                    Pool pool = app.bootstrap().services().pool();
                    orgA = cf.resultAt(0);
                    UUID orgC = cf.resultAt(2);
                    return TestPackages.createKey(pool, orgA, "orga-admin", "ADMIN")
                            .compose(k -> {
                                adminA = k;
                                return TestPackages.createKey(pool, cf.resultAt(1), "orgb-admin", "ADMIN");
                            })
                            .compose(k -> {
                                adminB = k;
                                return app.bootstrap().services().organisations().setActive(orgC, false);
                            });
                })
                .compose(o -> store(PKG, true))
                .compose(v -> store(PRIV, false))
                .compose(v -> CoreHarness.start(vertxB, connectOptions()))
                .onComplete(ctx.succeeding(h -> {
                    instanceB = h;
                    ctx.completeNow();
                }));
    }

    private Future<Void> store(String key, boolean shareable) {
        String[] nv = key.split("#");
        return TestPackages.storePackage(app.bootstrap().services(), TestPackages.profilePackage(nv[0], nv[1]))
                .compose(v -> TestPackages.setPackageAccess(app.bootstrap().services().pool(), key, orgA, shareable))
                .compose(v -> app.bootstrap().services().configSync().syncCatalogNow());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> stopA = app == null ? Future.succeededFuture() : app.stop();
        Future<Void> stopB = instanceB == null ? Future.succeededFuture() : instanceB.stop();
        Future.join(stopA, stopB).onComplete(ar -> vertxB.close().onComplete(v -> ctx.completeNow()));
    }

    private Future<HttpResponse<Buffer>> patchPackage(String key, String apiKey, JsonObject body) {
        String[] nv = key.split("#");
        HttpRequest<Buffer> req = AppHarness.as(app.client().patch(app.port(), "localhost", "/igs/" + nv[0] + "/" + nv[1]), apiKey)
                .timeout(15_000);
        return body == null ? req.send() : req.sendJsonObject(body);
    }

    private Future<HttpResponse<Buffer>> patchSnapshot(String key, String apiKey, JsonObject body) {
        HttpRequest<Buffer> req = AppHarness.as(app.client().patch(app.port(), "localhost", "/R4/include-ig"), apiKey)
                .addQueryParam("ig", key).timeout(15_000);
        return body == null ? req.send() : req.sendJsonObject(body);
    }

    private Future<HttpResponse<Buffer>> include(String name, String apiKey, Boolean shareable) {
        JsonObject body = new JsonObject().put("igPackageId", name).put("igPackageVersion", "1.0.0");
        if (shareable != null) {
            body.put("shareable", shareable);
        }
        return AppHarness.as(app.client().post(app.port(), "localhost", "/R4/include-ig"), apiKey).timeout(30_000)
                .sendJsonObject(body);
    }

    /**
     * Runs the requests one after another: PATCH is administrative, so more than {@code FV_ADMIN_MAX_PENDING} at
     * once would be refused with 503 overloaded.
     */
    @SafeVarargs
    private static Future<List<HttpResponse<Buffer>>> inSequence(java.util.function.Supplier<Future<HttpResponse<Buffer>>>... calls) {
        Future<List<HttpResponse<Buffer>>> chain = Future.succeededFuture(new java.util.ArrayList<>());
        for (var call : calls) {
            chain = chain.compose(list -> call.get().map(r -> {
                list.add(r);
                return list;
            }));
        }
        return chain;
    }

    private static JsonObject shareable(boolean value) {
        return new JsonObject().put("shareable", value);
    }

    private static JsonObject owner(String value) {
        return new JsonObject().put("owner", value);
    }

    @Test
    void packageAccessChangesFollowTheCheckOrder(VertxTestContext ctx) {
        inSequence(
                        () -> patchPackage(PRIV, adminB, shareable(true)),     // 0: not visible to B → 404
                        () -> patchPackage(PKG, adminB, shareable(false)),     // 1: visible but not B's → 404
                        () -> patchPackage(PRIV, adminB, owner("orgb")),       // 2: transfer by a non-owner, private → 404, not 403
                        () -> patchPackage(PKG, adminB, owner("orgb")),        // 3: transfer by a non-owner, shareable → 404, not 403
                        () -> patchPackage(PKG, adminA, owner("orgb")),        // 4: transfer by the owner → 403
                        () -> patchPackage("acc.none#1.0.0", adminA, shareable(true))) // 5: not stored → 404
                .compose(cf -> {
                    ctx.verify(() -> {
                        for (int i : new int[]{0, 1, 2, 3, 5}) {
                            assertProblem(cf.get(i), 404, "package-not-found");
                        }
                        assertProblem(cf.get(4), 403, "forbidden");
                        JsonObject notStored = cf.get(5).bodyAsJsonObject();
                        JsonObject hidden = cf.get(0).bodyAsJsonObject();
                        assertEquals(notStored.getString("detail").replace("acc.none", "acc.priv"), hidden.getString("detail"));
                    });
                    return inSequence(
                            () -> patchPackage(PKG, AppHarness.ADMIN_KEY, owner("nobody")),  // unknown target → 400
                            () -> patchPackage(PKG, AppHarness.ADMIN_KEY, owner("orgc")),    // inactive target → 400
                            () -> patchPackage(PKG, adminA, new JsonObject()),              // empty body → 400
                            () -> patchPackage(PKG, adminA, shareable(false)));             // the owner → 200
                })
                .compose(cf -> {
                    ctx.verify(() -> {
                        assertProblem(cf.get(0), 400, "invalid-request");
                        assertProblem(cf.get(1), 400, "invalid-request");
                        assertProblem(cf.get(2), 400, "invalid-request");
                        HttpResponse<Buffer> ok = cf.get(3);
                        assertEquals(200, ok.statusCode(), ok.bodyAsString());
                        assertEquals(new JsonObject().put("status", "success").put("name", "acc.pkg").put("version", "1.0.0")
                                .put("owner", "orga").put("shareable", false), ok.bodyAsJsonObject());
                    });
                    return patchPackage(PKG, AppHarness.ADMIN_KEY, owner("orgb").put("shareable", true));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals("orgb", r.bodyAsJsonObject().getString("owner"), "the system organisation transfers");
                    assertTrue(r.bodyAsJsonObject().getBoolean("shareable"));
                    ctx.completeNow();
                })));
    }

    @Test
    void snapshotAccessChangesFollowTheCheckOrder(VertxTestContext ctx) {
        // B includes A's shareable package: the context is B's, the package is A's.
        include("acc.pkg", adminB, null)
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return inSequence(
                            () -> patchSnapshot(PKG, adminA, shareable(false)),           // 0: not A's context → 404
                            () -> patchSnapshot(PKG, adminA, owner("orga")),              // 1: transfer by a non-owner → 404
                            () -> patchSnapshot(PKG, adminB, owner("orga")),              // 2: transfer by the owner → 403
                            () -> patchSnapshot(PKG, adminB, shareable(false)),           // 3: private, B doesn't own the package → 400
                            () -> patchSnapshot("acc.none#1.0.0", adminB, shareable(true)), // 4: not included → 404
                            () -> patchSnapshot(PKG, adminB, new JsonObject()));          // 5: empty body → 400
                })
                .compose(cf -> {
                    ctx.verify(() -> {
                        assertProblem(cf.get(0), 404, "package-not-found");
                        assertProblem(cf.get(1), 404, "package-not-found");
                        assertProblem(cf.get(2), 403, "forbidden");
                        JsonObject privateRefused = assertProblem(cf.get(3), 400, "invalid-request");
                        assertTrue(privateRefused.getString("detail").contains("Only the owner"));
                        assertProblem(cf.get(4), 404, "package-not-found");
                        assertProblem(cf.get(5), 400, "invalid-request");
                    });
                    return patchSnapshot(PKG, AppHarness.ADMIN_KEY, owner("orga").put("shareable", false));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals("orga", body.getString("owner"));
                    assertFalse(body.getBoolean("shareable"));
                    assertEquals("acc.pkg#1.0.0", body.getString("igPackage"));
                    assertNotNull(body.getLong("revision"));
                    ctx.completeNow();
                })));
    }

    @Test
    void changesReachTheOtherInstanceWithoutARebuild(Vertx vertx, VertxTestContext ctx) {
        SnapshotKey key = SnapshotKey.of(SupportedFhirVersion.R4, PKG);
        AtomicReference<ValidatorSnapshot> before = new AtomicReference<>();
        include("acc.pkg", adminA, null)
                .compose(r -> Await.until(vertx, 10_000, "B pins the context", () -> Future.succeededFuture(
                        instanceB.services().validatorRegistry().status(key) == nzi.fhir.validator.core.validator.ValidatorRegistry.Status.ACTIVE)))
                .compose(ms -> {
                    before.set(instanceB.services().validatorRegistry().current(key));
                    return patchSnapshot(PKG, adminA, shareable(false));
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return Await.until(vertx, 10_000, "the snapshot change reaches B", () -> Future.succeededFuture(
                            !instanceB.services().configSync().pinnedAccess(SupportedFhirVersion.R4).get(PKG).shareable()));
                })
                .compose(ms -> patchPackage(PKG, adminA, shareable(false)))
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return Await.until(vertx, 10_000, "the package change reaches B", () -> Future.succeededFuture(
                            !instanceB.services().configSync().catalog().current().access(PKG).shareable()));
                })
                .onComplete(ctx.succeeding(ms -> ctx.verify(() -> {
                    assertSame(before.get(), instanceB.services().validatorRegistry().current(key), "no rebuild on B");
                    ctx.completeNow();
                })));
    }
}
