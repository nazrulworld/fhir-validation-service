package nzi.fhir.validator.core.validator;

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
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.core.enums.ServedVersions;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.LogCapture;
import nzi.fhir.validator.testsupport.TestPackages;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 US3 (T028 to T030): stored contexts follow {@code FV_ENABLED_FHIR_VERSIONS} at startup, the change reaches
 * other instances, and administrators can still change or remove inactive contexts.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class ContextActivationIT extends BaseTestContainer {
    private static final String R4_PKG = "act.r4#1.0.0";
    private static final String R5_PKG = "act.r5#1.0.0";

    private final List<AppHarness> apps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetSchema();
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> all = Future.succeededFuture();
        for (AppHarness a : apps) {
            all = all.eventually(a::stop);
        }
        all.onComplete(ar -> ctx.completeNow());
    }

    private static JsonObject config(String enabledVersions) {
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME,
                POSTGRES_PASSWORD);
        if (enabledVersions != null) {
            config.put("fhir", new JsonObject().put("enabledVersions", enabledVersions));
        }
        return config;
    }

    private Future<AppHarness> start(Vertx vertx, String enabledVersions) {
        return AppHarness.start(vertx, config(enabledVersions)).compose(a -> {
            apps.add(a);
            return a.awaitReady(120_000).map(a);
        });
    }

    private Future<Void> stop(AppHarness a) {
        apps.remove(a);
        return a.stop();
    }

    /** Stores an R4 and an R5 IG and includes both through the API of {@code a} (which must serve both). */
    private static Future<Void> includeBoth(AppHarness a) {
        var services = a.bootstrap().services();
        byte[] r4 = TestPackages.profilePackage("act.r4", "1.0.0", TestPackages.requiredPatientElements(
                "http://example.org/fhir/StructureDefinition/act-r4", "birthDate"));
        byte[] r5 = TestPackages.igPackage("act.r5", "1.0.0", List.of("5.0.0"), Map.of());
        return TestPackages.storePackage(services, r4)
                .compose(v -> TestPackages.storePackage(services, r5))
                .compose(v -> include(a, "R4", "act.r4"))
                .compose(r -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    return include(a, "R5", "act.r5");
                })
                .map(r -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    return null;
                });
    }

    private static Future<HttpResponse<Buffer>> include(AppHarness a, String version, String id) {
        return AppHarness.asAdmin(a.client().post(a.port(), "localhost", "/" + version + "/validator").timeout(90_000))
                .sendJsonObject(new JsonObject().put("igPackageId", id));
    }

    private static HttpRequest<Buffer> admin(AppHarness a, String method, String path) {
        HttpRequest<Buffer> r = switch (method) {
            case "PATCH" -> a.client().patch(a.port(), "localhost", path);
            case "DELETE" -> a.client().delete(a.port(), "localhost", path);
            default -> a.client().post(a.port(), "localhost", path);
        };
        return AppHarness.asAdmin(r.timeout(60_000));
    }

    private static Future<List<String>> rows(Pool pool) {
        return pool.query("SELECT fhir_version || '|' || ig_package || '|' || is_active || '|' || coalesce(inactive_reason, '') "
                        + "FROM fhir_validator_schema.fhir_validator_snapshots ORDER BY 1").execute()
                .map(rs -> {
                    List<String> out = new ArrayList<>();
                    rs.forEach(r -> out.add(r.getString(0)));
                    return out;
                });
    }

    private static List<String> messages(LogCapture logs, Level level) {
        return logs.from(ContextActivation.class.getName()).stream().filter(e -> e.level() == level)
                .map(LogCapture.Captured::message).distinct().toList();
    }

    /** US3-1, US3-2, US3-5, FR-010, FR-012, FR-014, FR-015. */
    @Test
    void disablingAVersionDeactivatesItsContextsAndEnablingItAgainReactivatesThem(Vertx vertx, VertxTestContext ctx) {
        AppHarness[] current = new AppHarness[1];
        LogCapture[] logs = new LogCapture[1];
        start(vertx, "R4,R5")
                .compose(a -> includeBoth(a).map(a))
                .compose(this::stop)
                .compose(v -> {
                    logs[0] = LogCapture.start();
                    return start(vertx, "R5");
                })
                .compose(a -> {
                    current[0] = a;
                    return Future.all(rows(a.bootstrap().services().pool()),
                            a.client().get(a.port(), "localhost", "/health").send(),
                            a.client().get(a.port(), "localhost", "/health/readiness").send());
                })
                .compose(cf -> {
                    try (LogCapture l = logs[0]) {
                        ctx.verify(() -> {
                            assertEquals(List.of("R4|" + R4_PKG + "|false|fhir-version-disabled", "R5|" + R5_PKG + "|true|"),
                                    cf.<List<String>>resultAt(0));
                            assertEquals(List.of("Validator context deactivated: R4 " + R4_PKG + " (owner system): FHIR version "
                                            + "R4 is not enabled (FV_ENABLED_FHIR_VERSIONS)",
                                    "1 validator context(s) deactivated because their FHIR version is not enabled"),
                                    messages(l, Level.WARN));
                            JsonObject inactive = cf.<HttpResponse<Buffer>>resultAt(1).bodyAsJsonObject()
                                    .getJsonObject("fhirVersions").getJsonObject("inactiveContexts");
                            assertEquals(1, inactive.getInteger("count"));
                            assertEquals(0, inactive.getInteger("private"));
                            assertEquals(new JsonArray().add(new JsonObject().put("fhirVersion", "R4").put("igPackage", R4_PKG)
                                    .put("owner", "system").put("reason", "fhir-version-disabled")), inactive.getJsonArray("items"));
                            JsonObject readiness = cf.<HttpResponse<Buffer>>resultAt(2).bodyAsJsonObject();
                            assertEquals(java.util.Set.of("R5"), readiness.getJsonObject("versions").fieldNames());
                        });
                    }
                    return stop(current[0]);
                })
                // Restarting with the same setting changes nothing and logs nothing (FR-012).
                .compose(v -> {
                    logs[0] = LogCapture.start();
                    return start(vertx, "R5");
                })
                .compose(a -> {
                    try (LogCapture l = logs[0]) {
                        ctx.verify(() -> assertEquals(List.of(), messages(l, Level.WARN)));
                    }
                    return stop(a);
                })
                .compose(v -> {
                    logs[0] = LogCapture.start();
                    return start(vertx, "R4,R5");
                })
                .compose(a -> rows(a.bootstrap().services().pool()).map(r -> {
                    try (LogCapture l = logs[0]) {
                        ctx.verify(() -> {
                            assertEquals(List.of("R4|" + R4_PKG + "|true|", "R5|" + R5_PKG + "|true|"), r);
                            assertEquals(List.of("Validator context reactivated: R4 " + R4_PKG + " (owner system)",
                                    "1 validator context(s) reactivated"), messages(l, Level.INFO));
                            assertEquals(ValidatorRegistry.Status.ACTIVE,
                                    a.bootstrap().services().validatorRegistry().status(SnapshotKey.of(SupportedFhirVersion.R4, R4_PKG)),
                                    "the reactivated context is built");
                        });
                    }
                    return null;
                }))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** US3-3, FR-011: a failing transaction changes nothing and is a fatal startup error. */
    @Test
    void aFailedReconciliationChangesNothing(Vertx vertx, VertxTestContext ctx) {
        start(vertx, "R4,R5")
                .compose(a -> includeBoth(a).map(a))
                .compose(a -> {
                    Pool pool = a.bootstrap().services().pool();
                    // A trigger that fails the revision bump: the deactivation in the same transaction must roll back.
                    return pool.query("CREATE FUNCTION fhir_validator_schema.t_fail() RETURNS trigger AS $$ BEGIN "
                                    + "RAISE EXCEPTION 'boom'; END; $$ LANGUAGE plpgsql").execute()
                            .compose(v -> pool.query("CREATE TRIGGER t_fail BEFORE UPDATE ON fhir_validator_schema.fhir_validator_logs "
                                    + "FOR EACH ROW EXECUTE FUNCTION fhir_validator_schema.t_fail()").execute())
                            .compose(v -> ContextActivation.reconcile(pool, ServedVersions.parse("R5")).transform(ar -> {
                                ctx.verify(() -> {
                                    assertTrue(ar.failed());
                                    assertInstanceOf(StartupException.class, ar.cause());
                                    assertTrue(ar.cause().getMessage().startsWith("validator context activation could not be stored"));
                                });
                                return pool.query("DROP TRIGGER t_fail ON fhir_validator_schema.fhir_validator_logs").execute();
                            }))
                            .compose(v -> rows(pool));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(List.of("R4|" + R4_PKG + "|true|", "R5|" + R5_PKG + "|true|"), r, "rolled back");
                    ctx.completeNow();
                })));
    }

    /** T029, FR-013, SC-004: an instance still serving R4 drops the context once another one deactivates it. */
    @Test
    void anotherInstanceUnloadsDeactivatedContexts(Vertx vertx, VertxTestContext ctx) {
        unloadsOnOtherInstance(vertx, ctx, true);
    }

    /** As above, with notifications ignored: the poll fallback catches the bumped revision. */
    @Test
    void anotherInstanceUnloadsDeactivatedContextsByPolling(Vertx vertx, VertxTestContext ctx) {
        unloadsOnOtherInstance(vertx, ctx, false);
    }

    private void unloadsOnOtherInstance(Vertx vertx, VertxTestContext ctx, boolean notifications) {
        SnapshotKey r4 = SnapshotKey.of(SupportedFhirVersion.R4, R4_PKG);
        start(vertx, "R4,R5")
                .compose(a -> includeBoth(a).map(a))
                .compose(a -> {
                    a.bootstrap().services().configSync().setNotificationsEnabled(notifications);
                    ctx.verify(() -> assertEquals(ValidatorRegistry.Status.ACTIVE, a.bootstrap().services().validatorRegistry().status(r4)));
                    return start(vertx, "R5").map(b -> a);
                })
                .compose(a -> Await.until(vertx, 30_000, "the R4 context is dropped on the instance still serving R4",
                                () -> Future.succeededFuture(!a.bootstrap().services().validatorRegistry().details()
                                        .getJsonObject("R4").encode().contains(R4_PKG)))
                        // A dropped (inactive) context is refused like one that isn't included (400), never 503.
                        .compose(v -> AppHarness.asValidator(a.client().post(a.port(), "localhost", "/R4/validate?ig="
                                        + java.net.URLEncoder.encode(R4_PKG, java.nio.charset.StandardCharsets.UTF_8)))
                                .putHeader("Content-Type", "application/fhir+json")
                                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}"))))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(400, r.statusCode(), r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** T030, FR-032, US3-6: an inactive context can be changed and removed; adding one for its version is 422. */
    @Test
    void administratorsCanChangeAndRemoveInactiveContexts(Vertx vertx, VertxTestContext ctx) {
        String query = "?ig=" + java.net.URLEncoder.encode(R4_PKG, java.nio.charset.StandardCharsets.UTF_8);
        start(vertx, "R4,R5")
                .compose(a -> includeBoth(a).map(a))
                .compose(this::stop)
                .compose(v -> start(vertx, "R5"))
                .compose(a -> admin(a, "PATCH", "/R4/validator" + query).sendJsonObject(new JsonObject().put("shareable", false))
                        .compose(patched -> {
                            ctx.verify(() -> {
                                assertEquals(200, patched.statusCode(), patched.bodyAsString());
                                assertEquals(false, patched.bodyAsJsonObject().getBoolean("shareable"));
                            });
                            return include(a, "R4", "act.r4");
                        })
                        .compose(refused -> {
                            ctx.verify(() -> assertProblem(refused, 422, "fhir-version-disabled"));
                            return a.bootstrap().services().pool().query("SELECT is_active, is_shareable FROM "
                                    + "fhir_validator_schema.fhir_validator_snapshots WHERE ig_package = '" + R4_PKG + "'").execute();
                        })
                        .compose(rs -> {
                            ctx.verify(() -> {
                                var row = rs.iterator().next();
                                assertFalse(row.getBoolean(0), "still inactive");
                                assertFalse(row.getBoolean(1), "the change is stored");
                            });
                            return stop(a);
                        }))
                .compose(v -> start(vertx, "R4,R5"))
                .compose(a -> a.bootstrap().services().pool().query("SELECT is_active, is_shareable FROM "
                                + "fhir_validator_schema.fhir_validator_snapshots WHERE ig_package = '" + R4_PKG + "'").execute()
                        .compose(rs -> {
                            ctx.verify(() -> {
                                var row = rs.iterator().next();
                                assertTrue(row.getBoolean(0), "reactivated");
                                assertFalse(row.getBoolean(1), "with the change made while inactive");
                            });
                            return stop(a);
                        }))
                .compose(v -> start(vertx, "R5"))
                .compose(a -> admin(a, "DELETE", "/R4/validator" + query).send()
                        .compose(deleted -> {
                            ctx.verify(() -> assertEquals(200, deleted.statusCode(), deleted.bodyAsString()));
                            return rows(a.bootstrap().services().pool());
                        }))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(List.of("R5|" + R5_PKG + "|true|"), r, "the inactive context is gone");
                    ctx.completeNow();
                })));
    }

    /** Code review of PR #72: /health is unauthenticated, so a private inactive context is counted, never named. */
    @Test
    void privateInactiveContextsAreCountedButNotNamed(Vertx vertx, VertxTestContext ctx) {
        String query = "?ig=" + java.net.URLEncoder.encode(R4_PKG, java.nio.charset.StandardCharsets.UTF_8);
        start(vertx, "R4,R5")
                .compose(a -> includeBoth(a)
                        .compose(v -> admin(a, "PATCH", "/R4/validator" + query).sendJsonObject(new JsonObject().put("shareable", false)))
                        .compose(r -> {
                            ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                            return stop(a);
                        }))
                .compose(v -> start(vertx, "R5"))
                .compose(a -> a.client().get(a.port(), "localhost", "/health").send())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject inactive = r.bodyAsJsonObject().getJsonObject("fhirVersions").getJsonObject("inactiveContexts");
                    assertEquals(1, inactive.getInteger("count"));
                    assertEquals(1, inactive.getInteger("private"));
                    assertEquals(new JsonArray(), inactive.getJsonArray("items"));
                    assertFalse(r.bodyAsString().contains("act.r4"), "the private context is not named");
                    ctx.completeNow();
                })));
    }
}
