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
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 US4 and US2 (FR-001 to FR-005, FR-010 a, FR-012, FR-012a, SC-006): the terminology setting of an included
 * context is set on include, changed with PATCH by its owner or the system organisation, checked against the allowlist
 * when saved, and propagated to every instance, which rebuilds the context. Two instances share one database.
 * <p>
 * The allowlist comes from rows written to {@code tx_registry_snapshot} before the instances start (the "last good
 * list" the registry left behind): R4 lists {@code https://tx.fhir.ch/r4} and {@code http://tx.fhir.org/r4}; STU3
 * has a registry list with no server; R5 has none, so its built-in fallback list ({@code http://tx.fhir.org/r5})
 * applies. Nothing is fetched from the network (the test JVM keeps the production registry client offline).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologySettingIT extends BaseTestContainer {
    private static final long PROPAGATION_MS = 10_000;
    private static final String CH = "https://tx.fhir.ch/r4";

    private AppHarness a;
    private AppHarness b;
    private String keyA;
    private String keyB;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) throws Exception {
        startContainers();
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        String table = ApplicationConfig.DB_POSTGRES_SCHEMA_NAME + ".tx_registry_snapshot";
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            JsonArray r4 = new JsonArray()
                    .add(new JsonObject().put("url", CH).put("fhirVersion", "R4").put("serverCode", "hl7-ch"))
                    .add(new JsonObject().put("url", "http://tx.fhir.org/r4").put("fhirVersion", "R4").put("serverCode", "tx.fhir.org"));
            st.execute("INSERT INTO " + table + " (fhir_version, fetched_at, body) VALUES ('R4', now(), '" + r4.encode() + "'), "
                    + "('STU3', now(), '[]')");
        }
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        AppHarness.start(vertx, config.copy())
                .compose(h -> {
                    a = h;
                    return AppHarness.start(vertx, config.copy());
                })
                .compose(h -> {
                    b = h;
                    return Future.all(a.awaitReady(90_000), b.awaitReady(90_000));
                })
                .compose(v -> {
                    Pool pool = a.bootstrap().services().pool();
                    return Future.all(TestPackages.createOrganisation(pool, "txorga"), TestPackages.createOrganisation(pool, "txorgb"));
                })
                .compose(cf -> {
                    Pool pool = a.bootstrap().services().pool();
                    UUID orgA = cf.resultAt(0);
                    UUID orgB = cf.resultAt(1);
                    return TestPackages.createKey(pool, orgA, "txorga-admin", "ADMIN")
                            .compose(k -> {
                                keyA = k;
                                return TestPackages.createKey(pool, orgB, "txorgb-admin", "ADMIN");
                            })
                            .map(k -> {
                                keyB = k;
                                return null;
                            });
                })
                .compose(v -> store(TestPackages.profilePackage("tx.r4", "1.0.0")))
                .compose(v -> store(TestPackages.profilePackage("tx.r4", "2.0.0")))
                .compose(v -> store(TestPackages.profilePackage("tx.r4", "3.0.0")))
                .compose(v -> store(TestPackages.profilePackage("tx.r4", "4.0.0")))
                .compose(v -> store(TestPackages.igPackage("tx.r5", "1.0.0", List.of("5.0.0"), Map.of())))
                .compose(v -> store(TestPackages.igPackage("tx.r5", "2.0.0", List.of("5.0.0"), Map.of())))
                .compose(v -> store(TestPackages.igPackage("tx.r5", "3.0.0", List.of("5.0.0"), Map.of())))
                .compose(v -> store(TestPackages.igPackage("tx.stu3", "1.0.0", List.of("3.0.2"), Map.of())))
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (b == null ? Future.<Void>succeededFuture() : b.stop())
                .eventually(() -> a == null ? Future.succeededFuture() : a.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private Future<Void> store(byte[] tgz) {
        return TestPackages.storePackage(a.bootstrap().services(), tgz);
    }

    private Future<HttpResponse<Buffer>> include(String fhirVersion, String name, String version, JsonObject terminology, String key) {
        JsonObject body = new JsonObject().put("igPackageId", name).put("igPackageVersion", version);
        if (terminology != null) {
            body.put("terminology", terminology);
        }
        return AppHarness.as(a.client().post(a.port(), "localhost", "/" + fhirVersion + "/validator"), key)
                .putHeader("Content-Type", "application/json").sendJsonObject(body);
    }

    private Future<HttpResponse<Buffer>> patch(String fhirVersion, String ig, JsonObject body, String key) {
        var req = a.client().patch(a.port(), "localhost", "/" + fhirVersion + "/validator").addQueryParam("ig", ig)
                .putHeader("Content-Type", "application/json");
        return (key == null ? AppHarness.asAdmin(req) : AppHarness.as(req, key)).sendJsonObject(body);
    }

    private static JsonObject server(String url) {
        return new JsonObject().put("mode", "server").put("server", url);
    }

    private static JsonObject ecosystem() {
        return new JsonObject().put("mode", "ecosystem");
    }

    /** The state of {@code ig} in an instance's status, or null when it isn't listed. */
    private static String state(AppHarness on, String fhirVersion, String ig) {
        JsonArray contexts = on.bootstrap().services().validatorRegistry().details().getJsonObject(fhirVersion).getJsonArray("contexts");
        return contexts.stream().map(o -> (JsonObject) o).filter(c -> ig.equals(c.getString("key")))
                .map(c -> c.getString("state")).findFirst().orElse(null);
    }

    /** The terminology shown for {@code ig} in an instance's status (FR-004), or null when it isn't listed. */
    private static JsonObject shown(AppHarness on, String fhirVersion, String ig) {
        JsonArray contexts = on.bootstrap().services().validatorRegistry().details().getJsonObject(fhirVersion).getJsonArray("contexts");
        return contexts.stream().map(o -> (JsonObject) o).filter(c -> ig.equals(c.getString("key")))
                .map(c -> c.getJsonObject("terminology")).findFirst().orElse(null);
    }

    @Test
    void includeWithAListedServerStoresAndEchoesTheSetting(VertxTestContext ctx) {
        include("R4", "tx.r4", "1.0.0", server(CH + "/"), keyA)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(server(CH), r.bodyAsJsonObject().getJsonObject("terminology"), "stored normalised");
                    assertEquals(server(CH), shown(a, "R4", "tx.r4#1.0.0"));
                    ctx.completeNow();
                })));
    }

    @Test
    void anUnlistedServerIs400AndNothingIsCommitted(VertxTestContext ctx) {
        include("R4", "tx.r4", "2.0.0", server("http://10.0.0.5/fhir"), keyA)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "tx-server-not-registered");
                    assertEquals("https://ecosystem.hl7.org/tx-reg", problem.getString("registry"));
                    assertNull(shown(a, "R4", "tx.r4#2.0.0"), "the include was not committed");
                    ctx.completeNow();
                })));
    }

    @Test
    void withNoRegistryListOnlyTheBuiltInServerIsAccepted(VertxTestContext ctx) {
        include("R5", "tx.r5", "1.0.0", server("https://term.tehik.ee/fhir"), keyA)
                .compose(r -> {
                    ctx.verify(() -> {
                        assertProblem(r, 503, "tx-registry-unavailable");
                        assertEquals("60", r.getHeader("Retry-After"));
                    });
                    return include("R5", "tx.r5", "1.0.0", server("http://tx.fhir.org/r5"), keyA);
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return include("R5", "tx.r5", "2.0.0", new JsonObject().put("mode", "none"), keyA);
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), "mode none is always accepted: " + r.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void stu3HasARegistryListWithNoServerSoEverySettingIsRefused(VertxTestContext ctx) {
        Future.all(include("STU3", "tx.stu3", "1.0.0", ecosystem(), keyA),
                        include("STU3", "tx.stu3", "1.0.0", server("http://tx.fhir.org/r3"), keyA))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertProblem(cf.resultAt(0), 400, "tx-server-not-registered");
                    assertProblem(cf.resultAt(1), 400, "tx-server-not-registered");
                    ctx.completeNow();
                })));
    }

    @Test
    void malformedSettingsAre400(VertxTestContext ctx) {
        Future.all(include("R4", "tx.r4", "2.0.0", new JsonObject().put("mode", "server"), keyA),
                        include("R4", "tx.r4", "2.0.0", new JsonObject().put("mode", "none").put("server", CH), keyA),
                        include("R4", "tx.r4", "2.0.0", new JsonObject().put("mode", "remote"), keyA))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    for (int i = 0; i < 3; i++) {
                        HttpResponse<Buffer> r = cf.resultAt(i);
                        assertEquals(400, r.statusCode(), r.bodyAsString());
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    void includingAnIncludedContextAgainIgnoresTheSettingWithAWarning(VertxTestContext ctx) {
        include("R5", "tx.r5", "3.0.0", null, keyA)
                .compose(r -> include("R5", "tx.r5", "3.0.0", server("http://tx.fhir.org/r5"), keyA))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    JsonObject body = r.bodyAsJsonObject();
                    assertEquals(new JsonObject().put("mode", "none"), body.getJsonObject("terminology"), "unchanged");
                    assertTrue(body.getJsonArray("warnings").contains("terminology is ignored for an already included context; "
                            + "change it with PATCH /{version}/validator"), body.encode());
                    ctx.completeNow();
                })));
    }

    /** US4-1, US4-2, SC-006: a change by the owner reaches the other instance, which rebuilds the context. */
    @Test
    void aChangeByTheOwnerRebuildsTheContextOnEveryInstance(Vertx vertx, VertxTestContext ctx) {
        String ig = "tx.r4#3.0.0";
        long[] revision = new long[1];
        include("R4", "tx.r4", "3.0.0", null, keyA)
                .compose(r -> Await.until(vertx, PROPAGATION_MS, "B builds it", () -> Future.succeededFuture(shown(b, "R4", ig) != null)))
                .compose(ms -> patch("R4", ig, new JsonObject().put("terminology", ecosystem()), keyA))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), r.bodyAsString());
                        assertEquals(ecosystem(), r.bodyAsJsonObject().getJsonObject("terminology"));
                    });
                    revision[0] = r.bodyAsJsonObject().getLong("revision");
                    // The status shows the new setting as soon as the rebuild starts; wait until it has finished.
                    return Await.until(vertx, PROPAGATION_MS, "B rebuilds with the new setting",
                            () -> Future.succeededFuture(ecosystem().equals(shown(b, "R4", ig))
                                    && "ACTIVE".equals(state(b, "R4", ig))));
                })
                .compose(ms -> patch("R4", ig, new JsonObject().put("terminology", ecosystem()), keyA))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(revision[0], r.bodyAsJsonObject().getLong("revision"), "the same value changes nothing");
                    assertEquals("ACTIVE", state(b, "R4", ig), "no rebuild was started by the unchanged value");
                    ctx.completeNow();
                })));
    }

    /** US4-3, contracts §3: other organisations get 404 (even with an unlisted server: 404 comes first). */
    @Test
    void onlyTheOwnerOrTheSystemOrganisationMayChangeIt(VertxTestContext ctx) {
        String ig = "tx.r4#4.0.0";
        include("R4", "tx.r4", "4.0.0", null, keyA)
                .compose(r -> patch("R4", ig, new JsonObject().put("terminology", server("http://10.0.0.5/fhir")), keyB))
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 404, "package-not-found"));
                    return patch("R4", ig, new JsonObject().put("terminology", server(CH)), null);
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), "the system organisation may: " + r.bodyAsString()));
                    return patch("R4", ig, new JsonObject().put("terminology", server("http://10.0.0.5/fhir")), keyA);
                })
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "tx-server-not-registered"));
                    return patch("R4", ig, new JsonObject().put("color", "blue"), keyA);
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(400, r.statusCode(), r.bodyAsString());
                    assertEquals(server(CH), shown(a, "R4", ig), "the refused changes left the setting alone");
                    ctx.completeNow();
                })));
    }

    /** Contracts §5: the health report lists each context's terminology. */
    @Test
    void healthShowsTheSettingAndTheAllowlist(VertxTestContext ctx) {
        a.client().get(a.port(), "localhost", "/health").send()
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject body = r.bodyAsJsonObject();
                    JsonObject allowlist = body.getJsonObject("terminology").getJsonObject("allowlist");
                    assertEquals("stored-registry", allowlist.getJsonObject("R4").getString("source"));
                    assertEquals(2, allowlist.getJsonObject("R4").getInteger("servers"));
                    assertEquals("built-in", allowlist.getJsonObject("R5").getString("source"));
                    JsonArray contexts = body.getJsonObject("readiness").getJsonObject("versions").getJsonObject("R4")
                            .getJsonArray("contexts");
                    assertTrue(contexts.stream().map(o -> (JsonObject) o).allMatch(c -> c.containsKey("terminology")),
                            contexts.encode());
                    ctx.completeNow();
                })));
    }
}
