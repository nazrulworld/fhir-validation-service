package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.DatabaseService;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import io.vertx.junit5.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 US1 (FR-001, FR-003, FR-011, SC-001): a context's terminology setting is applied per request. Changing it
 * reaches the next request without a rebuild (the same snapshot keeps serving), no answer cached for one server is
 * served for another, and a default server removed from the allowlist fails closed for terminology only.
 * <p>
 * Two local stub servers play A and B; the stored registry list (written before start) allows both for R4, and the
 * terminology clients use a loopback-allowing guard (test hook). Nothing reaches the network.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TerminologyNoRebuildIT extends BaseTestContainer {
    static final String LOINC = "http://loinc.org";
    static final String IG = TestPackages.TX_IG_NAME + "#" + TestPackages.TX_IG_VERSION;
    private static final long PROPAGATION_MS = 10_000;

    private final StubTerminologyServer a = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
    private final StubTerminologyServer b = new StubTerminologyServer("/r4").code(LOINC, "8867-4", "Heart rate");
    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) throws Exception {
        ServiceRegistry.terminologyGuardForTests = TestTerminology.loopbackGuard();
        startContainers();
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        a.start(vertx).compose(x -> b.start(vertx))
                .compose(x -> {
                    try {
                        storeAllowlist(a.base(), b.base());
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                    return AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(),
                            POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD));
                })
                .compose(h -> {
                    app = h;
                    return h.awaitReady(90_000);
                })
                .compose(v -> TestPackages.storePackage(app.bootstrap().services(), TestPackages.terminologyIg()))
                .compose(v -> include(server(a.base())))
                .compose(r -> Await.until(vertx, 60_000, "the context is built",
                        () -> snapshot().map(s -> true).otherwise(false)))
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        ServiceRegistry.terminologyGuardForTests = null;
        (app == null ? Future.<Void>succeededFuture() : app.stop())
                .eventually(() -> a.stop()).eventually(() -> b.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    /** The stored registry list for R4 ("last good list"), read again by the running registry. */
    void storeAllowlist(String... urls) throws Exception {
        JsonArray body = new JsonArray();
        for (String url : urls) {
            body.add(new JsonObject().put("url", url).put("fhirVersion", "R4").put("serverCode", "stub"));
        }
        String table = ApplicationConfig.DB_POSTGRES_SCHEMA_NAME + ".tx_registry_snapshot";
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM " + table + " WHERE fhir_version = 'R4'");
            st.execute("INSERT INTO " + table + " (fhir_version, fetched_at, body) VALUES ('R4', now(), '" + body.encode() + "')");
        }
    }

    static JsonObject server(String url) {
        return new JsonObject().put("mode", "server").put("server", url);
    }

    private Future<HttpResponse<Buffer>> include(JsonObject terminology) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("igPackageId", TestPackages.TX_IG_NAME)
                        .put("igPackageVersion", TestPackages.TX_IG_VERSION).put("terminology", terminology));
    }

    private Future<HttpResponse<Buffer>> patch(JsonObject terminology) {
        return AppHarness.asAdmin(app.client().patch(app.port(), "localhost", "/R4/validator").addQueryParam("ig", IG))
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("terminology", terminology));
    }

    Future<HttpResponse<Buffer>> validate(String code) {
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validate")
                        .addQueryParam("ig", IG).addQueryParam("profile", TestPackages.TX_OBSERVATION_PROFILE))
                .putHeader("Content-Type", "application/fhir+json").putHeader("Accept", "application/json")
                .sendBuffer(Buffer.buffer(TestPackages.codedObservation(LOINC, code)));
    }

    private Future<ValidatorSnapshot> snapshot() {
        return app.bootstrap().services().validatorRegistry().snapshot(SnapshotKey.of(SupportedFhirVersion.R4, IG));
    }

    private JsonObject shown() {
        JsonArray contexts = app.bootstrap().services().validatorRegistry().details().getJsonObject("R4").getJsonArray("contexts");
        return contexts.stream().map(o -> (JsonObject) o).filter(c -> IG.equals(c.getString("key")))
                .map(c -> c.getJsonObject("terminology")).findFirst().orElse(null);
    }

    private Future<Void> changeTo(Vertx vertx, JsonObject terminology) {
        return patch(terminology).compose(r -> {
            assertEquals(200, r.statusCode(), r.bodyAsString());
            return Await.until(vertx, PROPAGATION_MS, "the new setting is applied",
                    () -> Future.succeededFuture(terminology.equals(shown())));
        }).mapEmpty();
    }

    static String text(HttpResponse<Buffer> r) {
        return r.bodyAsString();
    }

    /** US1-1, SC-001, FR-012: A then B; the same snapshot answers both; nothing cached from A is served for B. */
    @Test
    @Order(1)
    void aSettingChangeReachesTheNextRequestWithoutARebuild(Vertx vertx, VertxTestContext ctx) {
        long[] id = new long[1];
        snapshot().compose(s -> {
                    id[0] = s.snapshotId();
                    a.clear();
                    b.clear();
                    return validate("9999-9");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), text(r));
                        assertTrue(a.hits() >= 1, "A was asked");
                        assertEquals(0, b.hits());
                    });
                    return changeTo(vertx, server(b.base()));
                })
                .compose(v -> {
                    a.clear();
                    return validate("9999-9");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), text(r));
                        assertTrue(b.hits() >= 1, "B was asked: A's cached answer was not served for B");
                        assertEquals(0, a.hits(), "A is no longer asked");
                    });
                    return snapshot();
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    assertEquals(id[0], s.snapshotId(), "no rebuild");
                    ctx.completeNow();
                })));
    }

    /** US1-2: to none, the next request makes no outbound call; US1-3: back to a server, without a rebuild. */
    @Test
    @Order(2)
    void toNoneMakesNoCallAndBackAgain(Vertx vertx, VertxTestContext ctx) {
        long[] id = new long[1];
        snapshot().compose(s -> {
                    id[0] = s.snapshotId();
                    return changeTo(vertx, new JsonObject().put("mode", "none"));
                })
                .compose(v -> {
                    a.clear();
                    b.clear();
                    return validate("9999-8");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), text(r));
                        assertEquals(0, a.hits() + b.hits(), "mode none: zero outbound calls");
                    });
                    return changeTo(vertx, server(a.base()));
                })
                .compose(v -> validate("9999-8"))
                .compose(r -> {
                    ctx.verify(() -> assertTrue(a.hits() >= 1, "A is asked again"));
                    return snapshot();
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    assertEquals(id[0], s.snapshotId(), "no rebuild");
                    ctx.completeNow();
                })));
    }

    /** US1-4, FR-011: A leaves the allowlist; the next request fails closed for terminology only, without a rebuild. */
    @Test
    @Order(3)
    void aDefaultServerRemovedFromTheAllowlistFailsClosed(Vertx vertx, VertxTestContext ctx) throws Exception {
        long[] id = new long[1];
        changeTo(vertx, server(a.base()))
                .compose(v -> snapshot())
                .compose(s -> {
                    id[0] = s.snapshotId();
                    try {
                        storeAllowlist(b.base());
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                    return TestTerminology.reloadStored(app.bootstrap().services().txRegistry());
                })
                .compose(v -> {
                    a.clear();
                    return validate("9999-7");
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), text(r));
                        assertEquals(0, a.hits(), "no call to a server that is no longer allowed");
                        assertTrue(text(r).contains("No permitted terminology server for " + LOINC), text(r));
                    });
                    try {
                        storeAllowlist(a.base(), b.base());
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                    return TestTerminology.reloadStored(app.bootstrap().services().txRegistry());
                })
                .compose(v -> validate("9999-7"))
                .compose(r -> {
                    ctx.verify(() -> assertTrue(a.hits() >= 1, "allowed again: A is asked"));
                    return snapshot();
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    assertEquals(id[0], s.snapshotId(), "no rebuild");
                    ctx.completeNow();
                })));
    }
}
