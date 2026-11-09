package nzi.fhir.validator.core.terminology;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.StubTxRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R4;
import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R4B;
import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R5;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 FR-007, FR-012, research R10: the allowlist is fetched from the registry (a local stub here), stored as the
 * last good copy, read back by a restarted instance while the registry is down, and replaced by the built-in list
 * only when no list was ever obtained.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class TxRegistryIT extends BaseTestContainer {
    private static final String TABLE = ApplicationConfig.DB_POSTGRES_SCHEMA_NAME + ".tx_registry_snapshot";

    private CoreHarness core;
    private StubTxRegistry stub;
    private TxRegistry registry;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        stub = new StubTxRegistry();
        stub.start(vertx).compose(s -> CoreHarness.start(vertx, connectOptions()))
                .onComplete(ctx.succeeding(h -> {
                    core = h;
                    ctx.completeNow();
                }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (registry != null) {
            registry.stop();
        }
        (core == null ? Future.<Void>succeededFuture() : core.stop()).eventually(() -> stub.stop())
                .onComplete(ar -> ctx.completeNow());
    }

    private TxRegistry newRegistry(Vertx vertx, long refreshMs) {
        return new TxRegistry(vertx, pool(), stub.base(), NetworkGuard.forTests(), refreshMs, 2_000,
                TxServerAllowlist.builtIn());
    }

    private Pool pool() {
        return core.services().pool();
    }

    @Test
    void fetchesEveryVersionAndStoresTheLastGoodCopy(Vertx vertx, VertxTestContext ctx) {
        registry = newRegistry(vertx, 3_600_000);
        registry.start()
                .compose(v -> Await.until(vertx, 20_000, "every version fetched", () -> Future.succeededFuture(
                        registry.current().list(R5).map(l -> l.source() == TxServerAllowlist.Source.REGISTRY).orElse(false))))
                .compose(ms -> pool().query("SELECT fhir_version || ':' || jsonb_array_length(body) FROM " + TABLE
                        + " ORDER BY fhir_version").execute())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    TxServerAllowlist list = registry.current();
                    assertTrue(list.isAllowed("http://tx.fhir.org/r4", R4));
                    assertEquals(14, list.list(R4).orElseThrow().entries().size());
                    assertEquals(0, list.list(R4B).orElseThrow().entries().size());
                    assertNotNull(list.list(R4).orElseThrow().registryLastUpdate());
                    java.util.List<String> stored = new java.util.ArrayList<>();
                    rows.forEach(r -> stored.add(r.getString(0)));
                    assertEquals(java.util.List.of("R4:14", "R4B:0", "R5:5", "STU3:0"), stored);
                    assertTrue(stub.accepts().stream().allMatch(a -> a.equals("application/json")), stub.accepts().toString());
                    assertTrue(stub.queries().contains("fhirVersion=R3"), "STU3 is asked for as R3: " + stub.queries());
                    ctx.completeNow();
                })));
    }

    @Test
    void aFailedRefreshKeepsThePreviousList(Vertx vertx, VertxTestContext ctx) {
        registry = newRegistry(vertx, 3_600_000);
        registry.refreshNow()
                .compose(v -> {
                    stub.mode(StubTxRegistry.Mode.UNAVAILABLE);
                    return registry.refreshNow();
                })
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertEquals(TxServerAllowlist.Source.REGISTRY, registry.current().list(R4).orElseThrow().source());
                    assertTrue(registry.current().isAllowed("https://tx.fhir.ch/r4", R4));
                    ctx.completeNow();
                })));
    }

    @Test
    void aRestartWithTheRegistryDownUsesTheStoredList(Vertx vertx, VertxTestContext ctx) {
        registry = newRegistry(vertx, 3_600_000);
        registry.refreshNow()
                .compose(v -> {
                    registry.stop();
                    stub.mode(StubTxRegistry.Mode.UNAVAILABLE);
                    registry = newRegistry(vertx, 3_600_000);
                    return registry.start();
                })
                .compose(v -> vertx.timer(500))
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    TxServerAllowlist.VersionList r4 = registry.current().list(R4).orElseThrow();
                    assertEquals(TxServerAllowlist.Source.STORED_REGISTRY, r4.source());
                    assertTrue(registry.current().isAllowed("https://tx.fhir.ch/r4", R4));
                    assertTrue(registry.current().hasRegistryList(R4));
                    assertEquals("stored-registry", registry.status().getJsonObject("allowlist").getJsonObject("R4").getString("source"));
                    ctx.completeNow();
                })));
    }

    @Test
    void withNothingStoredAndTheRegistryDownTheBuiltInListApplies(Vertx vertx, VertxTestContext ctx) {
        stub.mode(StubTxRegistry.Mode.UNAVAILABLE);
        registry = newRegistry(vertx, 3_600_000);
        registry.start().compose(v -> vertx.timer(500)).onComplete(ctx.succeeding(v -> ctx.verify(() -> {
            TxServerAllowlist list = registry.current();
            assertEquals(TxServerAllowlist.Source.BUILT_IN, list.list(R4).orElseThrow().source());
            assertTrue(list.isAllowed("http://tx.fhir.org/r4", R4));
            assertFalse(list.isAllowed("https://tx.fhir.ch/r4", R4));
            assertFalse(list.hasRegistryList(R4));
            assertEquals("built-in", registry.status().getJsonObject("allowlist").getJsonObject("R4").getString("source"));
            ctx.completeNow();
        })));
    }

    @Test
    void anOlderFetchNeverOverwritesANewerStoredRow(Vertx vertx, VertxTestContext ctx) {
        registry = newRegistry(vertx, 3_600_000);
        pool().query("INSERT INTO " + TABLE + " (fhir_version, fetched_at, body) VALUES ('R4', now() + interval '1 day', '[]')")
                .execute()
                .compose(r -> registry.refreshNow())
                .compose(v -> pool().query("SELECT jsonb_array_length(body) FROM " + TABLE + " WHERE fhir_version = 'R4'").execute())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(0, rows.iterator().next().getInteger(0), "the newer row is kept");
                    assertEquals(14, registry.current().list(R4).orElseThrow().entries().size(),
                            "this instance still uses what it fetched");
                    ctx.completeNow();
                })));
    }

    @Test
    void refreshesPeriodically(Vertx vertx, VertxTestContext ctx) {
        registry = newRegistry(vertx, 300);
        registry.start()
                .compose(v -> Await.until(vertx, 10_000, "three rounds", () -> Future.succeededFuture(stub.discoveryHits() >= 12)))
                .onComplete(ctx.succeeding(ms -> ctx.completeNow()));
    }

    @Test
    void theProductionRegistryUsesTheOfficialAddressAndTheStrictGuard(Vertx vertx) {
        TxRegistry official = TxRegistry.official(vertx, null, 3_600_000, 1_000);
        assertEquals("https://ecosystem.hl7.org/tx-reg", official.base());
        assertFalse(official.guard().allowsLoopback());
        official.stop();
    }
}
