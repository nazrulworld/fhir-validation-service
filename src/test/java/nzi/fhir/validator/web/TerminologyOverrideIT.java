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
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.core.terminology.TestTerminology;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.StubTerminologyServer;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 US2 (FR-005 to FR-010, FR-016, SC-002, SC-003): a validate request chooses its terminology setting with
 * {@code tx}/{@code txMode}. The context (set to stub A) keeps serving every choice with the same snapshot; a server
 * that is not allowed is refused before validation with no call; the 200 response reports the setting used in
 * headers only. Stubs A and B are allowed for R4 by the stored registry list; R5 has no registry list (built-in
 * fallback only). The terminology clients use a loopback-allowing guard (test hook).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologyOverrideIT extends BaseTestContainer {
    static final String LOINC = "http://loinc.org";
    static final String IG = TestPackages.TX_IG_NAME + "#" + TestPackages.TX_IG_VERSION;
    private static final String UNLISTED = "https://unlisted.example.org/fhir";

    private final StubTerminologyServer a = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
    private final StubTerminologyServer b = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
    private AppHarness app;
    private long snapshotId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        ServiceRegistry.terminologyGuardForTests = TestTerminology.loopbackGuard();
        startContainers();
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        a.start(vertx).compose(x -> b.start(vertx))
                .compose(x -> {
                    storeAllowlist(a.base(), b.base());
                    JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                            POSTGRES_USERNAME, POSTGRES_PASSWORD);
                    // FR-016: a small budget, so a slow server uses it up within the test.
                    config.put("tx", new JsonObject().put("requestBudgetMs", 1000));
                    return AppHarness.start(vertx, config);
                })
                .compose(h -> {
                    app = h;
                    return h.awaitReady(90_000);
                })
                .compose(v -> TestPackages.storePackage(app.bootstrap().services(), TestPackages.terminologyIg()))
                .compose(v -> AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                        .putHeader("Content-Type", "application/json")
                        .sendJsonObject(new JsonObject().put("igPackageId", TestPackages.TX_IG_NAME)
                                .put("igPackageVersion", TestPackages.TX_IG_VERSION)
                                .put("terminology", new JsonObject().put("mode", "server").put("server", a.base()))))
                .compose(r -> Await.until(vertx, 60_000, "the context is built", () -> snapshot().map(s -> true).otherwise(false)))
                .compose(ms -> snapshot())
                .onComplete(ctx.succeeding(s -> {
                    snapshotId = s.snapshotId();
                    ctx.completeNow();
                }));
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        ServiceRegistry.terminologyGuardForTests = null;
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> a.stop()).eventually(() -> b.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    @BeforeEach
    void clearStubs() {
        a.normal();
        b.normal();
        a.clear();
        b.clear();
    }

    void storeAllowlist(String... urls) {
        JsonArray body = new JsonArray();
        for (String url : urls) {
            body.add(new JsonObject().put("url", url).put("fhirVersion", "R4").put("serverCode", "stub"));
        }
        String table = ApplicationConfig.DB_POSTGRES_SCHEMA_NAME + ".tx_registry_snapshot";
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM " + table + " WHERE fhir_version = 'R4'");
            st.execute("INSERT INTO " + table + " (fhir_version, fetched_at, body) VALUES ('R4', now(), '" + body.encode() + "')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Future<ValidatorSnapshot> snapshot() {
        return app.bootstrap().services().validatorRegistry().snapshot(SnapshotKey.of(SupportedFhirVersion.R4, IG));
    }

    /** POST /{version}/validate with {@code query} as raw query parameters (already encoded). */
    private Future<HttpResponse<Buffer>> validate(String version, String ig, String code, String query, String accept) {
        String uri = "/" + version + "/validate?" + (ig == null ? "" : "ig=" + ig.replace("#", "%23")
                + "&profile=" + TestPackages.TX_OBSERVATION_PROFILE + "&") + (query == null ? "" : query);
        HttpRequest<Buffer> req = AppHarness.asAdmin(app.client().post(app.port(), "localhost", uri))
                .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", accept);
        return req.sendBuffer(Buffer.buffer(TestPackages.codedObservation(LOINC, code)));
    }

    private Future<HttpResponse<Buffer>> validate(String query) {
        return validate("R4", IG, "9999-9", query, "application/json");
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertHeaders(HttpResponse<Buffer> r, String mode, String server, String source) {
        assertEquals(200, r.statusCode(), r.bodyAsString());
        assertEquals(mode, r.getHeader("X-Tx-Mode"));
        assertEquals(server, r.getHeader("X-Tx-Server"));
        assertEquals(source, r.getHeader("X-Tx-Source"));
    }

    private static void assertNoTxHeaders(HttpResponse<Buffer> r) {
        assertNull(r.getHeader("X-Tx-Mode"), "refusals carry no X-Tx-* header");
        assertNull(r.getHeader("X-Tx-Server"));
        assertNull(r.getHeader("X-Tx-Source"));
    }

    private Future<Void> sameSnapshot(VertxTestContext ctx) {
        return snapshot().map(s -> {
            ctx.verify(() -> assertEquals(snapshotId, s.snapshotId(), "no build: the same snapshot served every request"));
            return null;
        });
    }

    /** US2-1 to US2-3, SC-002: A by default, B on request, none on request; one snapshot; nothing cached across. */
    @Test
    void oneContextServesEveryChoice(VertxTestContext ctx) {
        // A code no other test checks, so the cache (per server) can't answer it.
        java.util.function.Function<String, Future<HttpResponse<Buffer>>> v =
                q -> validate("R4", IG, "5555-1", q, "application/json");
        v.apply(null)
                .compose(r -> {
                    ctx.verify(() -> {
                        assertHeaders(r, "server", a.base(), "context");
                        assertTrue(a.hits() >= 1);
                        assertEquals(0, b.hits());
                    });
                    a.clear();
                    return v.apply("tx=" + enc(b.base()));
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertHeaders(r, "server", b.base(), "request");
                        assertTrue(b.hits() >= 1, "B was asked: A's cached answer was not served for B");
                        assertEquals(0, a.hits());
                    });
                    a.clear();
                    b.clear();
                    return v.apply("txMode=none");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertHeaders(r, "none", null, "request");
                        assertEquals(0, a.hits() + b.hits(), "txMode=none: zero outbound calls");
                    });
                    return sameSnapshot(ctx);
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    /** FR-010, contracts §3: both response forms carry the headers; the body equals the one without parameters. */
    @Test
    void headersOnBothFormsAndAnUnchangedBody(VertxTestContext ctx) {
        validate("R4", IG, "8867-4", null, "application/fhir+json")
                .compose(plain -> validate("R4", IG, "8867-4", "txMode=server", "application/fhir+json").map(req -> {
                    ctx.verify(() -> {
                        assertHeaders(plain, "server", a.base(), "context");
                        assertHeaders(req, "server", a.base(), "request");
                        assertEquals(plain.bodyAsString(), req.bodyAsString(), "the setting is reported in headers only");
                    });
                    return null;
                }))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** US2-4, US2 ecosystem rows: the base context with txMode=ecosystem; ecosystem with tx=B names B. */
    @Test
    void ecosystemOnTheBaseContextAndWithTx(VertxTestContext ctx) {
        validate("R4", null, "9999-9", "txMode=ecosystem", "application/json")
                .compose(r -> {
                    ctx.verify(() -> assertHeaders(r, "ecosystem", null, "request"));
                    return validate("txMode=ecosystem&tx=" + enc(b.base()));
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertHeaders(r, "ecosystem", b.base(), "request");
                    assertEquals(0, a.hits(), "the context's own server is not used");
                    ctx.completeNow();
                })));
    }

    /** US2-5, FR-008: an unlisted server is refused before validation, with no call; R5 has no registry list: 503. */
    @Test
    void aServerThatIsNotAllowedIsRefusedWithNoCall(VertxTestContext ctx) {
        validate("tx=" + enc(UNLISTED))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertProblem(r, 400, "tx-server-not-registered");
                        assertNoTxHeaders(r);
                        assertEquals(0, a.hits() + b.hits());
                    });
                    return validate("R5", null, "9999-9", "tx=" + enc(UNLISTED), "application/json");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertProblem(r, 503, "tx-registry-unavailable");
                    assertNotNull(r.getHeader("Retry-After"), "the same retry hint as for saved settings");
                    assertNoTxHeaders(r);
                    ctx.completeNow();
                })));
    }

    /** US2-6, SC-003, FR-007: malformed input over HTTP gets the curated texts; the address is never echoed. */
    @Test
    void malformedInputIs400WithFixedTexts(VertxTestContext ctx) {
        String longTx = "https://tx.example.org/" + "a".repeat(490);
        Future.all(java.util.List.of(
                        validate("tx=" + enc(a.base()) + "&tx=" + enc(b.base())),
                        validate("tx=" + enc(longTx)),
                        validate("txMode=Server"),
                        validate("txMode=none&tx=" + enc(b.base())),
                        validate("tx="),
                        validate("tx=" + enc("ftp://tx.example.org/fhir"))))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    String[] texts = {"Give tx and txMode at most once each", "tx must be at most 512 characters",
                            "txMode must be none, server or ecosystem", "tx is not allowed with txMode=none",
                            "tx and txMode must not be empty",
                            "tx must be an http or https base URL without query, fragment or user info"};
                    for (int i = 0; i < texts.length; i++) {
                        HttpResponse<Buffer> r = cf.resultAt(i);
                        JsonObject problem = assertProblem(r, 400, "invalid-request");
                        assertEquals(texts[i], problem.getString("detail"));
                        assertFalse(r.bodyAsString().contains("tx.example.org"), "the address is never echoed");
                        assertNoTxHeaders(r);
                    }
                    ctx.completeNow();
                })));
    }

    /** FR-008a: resolution and access answers come before any terminology answer; the ig shape before tx's. */
    @Test
    void checkOrder(VertxTestContext ctx) {
        validate("R4", "not.stored.ig#1.0.0", "9999-9", "tx=" + enc(UNLISTED), "application/json")
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 404, "package-not-found"));
                    return validate("R4", "a#b#c", "9999-9", "txMode=Server", "application/json");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    JsonObject problem = assertProblem(r, 400, "invalid-request");
                    assertNotEquals("txMode must be none, server or ecosystem", problem.getString("detail"),
                            "the ig error comes first");
                    ctx.completeNow();
                })));
    }

    /** Edge cases: a denied default doesn't stop an override; naming the removed server again is 400. */
    @Test
    void aDeniedDefaultAndAnOverride(VertxTestContext ctx) {
        storeAllowlist(b.base());
        TestTerminology.reloadStored(app.bootstrap().services().txRegistry())
                .compose(v -> validate(null))
                .compose(r -> {
                    ctx.verify(() -> {
                        assertHeaders(r, "server", a.base(), "context");
                        assertTrue(r.bodyAsString().contains("No permitted terminology server"), r.bodyAsString());
                        assertEquals(0, a.hits());
                    });
                    return validate("tx=" + enc(b.base()));
                })
                .compose(r -> {
                    ctx.verify(() -> assertTrue(b.hits() >= 1, "the override is used"));
                    return validate("tx=" + enc(a.base()));
                })
                .compose(r -> {
                    ctx.verify(() -> assertProblem(r, 400, "tx-server-not-registered"));
                    storeAllowlist(a.base(), b.base());
                    return TestTerminology.reloadStored(app.bootstrap().services().txRegistry());
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * FR-016: the request budget (1 s here) applies to a request-chosen server: a call to B, which answers after 3 s,
     * is cut off at the budget, not at the 5 s call timeout, and reported as not checked (HTTP 200).
     */
    @Test
    void theBudgetAppliesToTheEffectiveSetting(VertxTestContext ctx) {
        b.delay(3_000);
        long started = System.nanoTime();
        validate("R4", IG, "1111-1", "tx=" + enc(b.base()), "application/json")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertTrue(r.bodyAsString().contains("code http://loinc.org#1111-1 was not checked"), r.bodyAsString());
                    assertTrue(ms < 2_800, "cut off by the 1 s request budget, took " + ms + " ms");
                    assertEquals(0, a.hits());
                    ctx.completeNow();
                })));
    }

    @Test
    void theTestSetupMatchesTheAllowlist() {
        assertEquals(TerminologySettings.normaliseUrl(a.base()), a.base(), "stub bases are already normalised");
    }

    /**
     * FR-008a: the effective setting and the allowlist are checked before context readiness. While a context is still
     * building, a request naming an unlisted tx gets 400 tx-server-not-registered, not 503 not-ready.
     */
    @Test
    void aRefusedTxComesBeforeNotReady(Vertx vertx, VertxTestContext ctx) {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var builder = app.bootstrap().services().snapshotBuilder();
        String building = "tx.building#1.0.0";
        builder.setBuildHook(() -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TestPackages.storePackage(app.bootstrap().services(), TestPackages.profilePackage("tx.building", "1.0.0",
                        TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/building", "gender")))
                .compose(v -> AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                        .putHeader("Content-Type", "application/json")
                        .sendJsonObject(new JsonObject().put("igPackageId", "tx.building").put("igPackageVersion", "1.0.0")))
                .compose(included -> {
                    ctx.verify(() -> assertEquals(200, included.statusCode(), included.bodyAsString()));
                    return validate("R4", building, "9999-9", null, "application/json");
                })
                .compose(notReady -> {
                    ctx.verify(() -> assertProblem(notReady, 503, "not-ready"));
                    return validate("R4", building, "9999-9", "tx=" + enc(UNLISTED), "application/json");
                })
                .onComplete(ar -> {
                    builder.setBuildHook(null);
                    release.countDown();
                    ctx.verify(() -> {
                        assertTrue(ar.succeeded(), String.valueOf(ar.cause()));
                        assertProblem(ar.result(), 400, "tx-server-not-registered");
                        assertNoTxHeaders(ar.result());
                    });
                    ctx.completeNow();
                });
    }

    /**
     * Research R4, FR-011: when the allowlist changes, the code results of ecosystem scopes are dropped (routing may
     * differ now); results of server scopes stay.
     */
    @Test
    void anAllowlistChangeDropsEcosystemResults(VertxTestContext ctx) {
        Future<String> metricsBefore = app.client().get(app.port(), "localhost", "/metrics").send().map(HttpResponse::bodyAsString);
        validate("R4", IG, "6666-1", null, "application/json")                                       // server:A scope
                .compose(r -> validate("R4", IG, "6666-2", "txMode=ecosystem&tx=" + enc(b.base()), "application/json"))
                .compose(r -> metricsBefore)
                .compose(before -> {
                    storeAllowlist(b.base(), a.base());            // the same servers, but a fresh list for R4 ...
                    storeAllowlist(b.base());                      // ... then A removed: the R4 list differs
                    return TestTerminology.reloadStored(app.bootstrap().services().txRegistry())
                            .compose(v -> app.client().get(app.port(), "localhost", "/metrics").send())
                            .map(after -> new String[]{before, after.bodyAsString()});
                })
                .compose(m -> {
                    ctx.verify(() -> assertTrue(allowlistDrops(m[1]) > allowlistDrops(m[0]),
                            "ecosystem results were dropped after the allowlist change"));
                    storeAllowlist(a.base(), b.base());
                    return TestTerminology.reloadStored(app.bootstrap().services().txRegistry());
                })
                .compose(v -> {
                    a.clear();
                    return validate("R4", IG, "6666-1", null, "application/json");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertHeaders(r, "server", a.base(), "context");
                    assertEquals(0, a.hits(), "the server-scope result stayed cached");
                    ctx.completeNow();
                })));
    }

    private static double allowlistDrops(String metrics) {
        return metrics.lines().filter(l -> l.startsWith("fv_tx_code_cache_invalidations_total{") && l.contains("reason=\"allowlist\""))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }
}
