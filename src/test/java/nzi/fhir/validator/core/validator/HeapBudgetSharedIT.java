package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.Fixtures;
import nzi.fhir.validator.testsupport.TestPackages;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 US4 (FR-024, FR-025): with DK Core 3.5.0 built, the heap limit is lowered so that a full reparse of MedCom
 * Core 4.0.0's packages would not fit, but its two own packages plus the indexes of the ten shared ones do. The
 * include is admitted and the context builds; the metrics show the reuse and the code cache.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 600, timeUnit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeapBudgetSharedIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        AppHarness.start(vertx, AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                        POSTGRES_USERNAME, POSTGRES_PASSWORD))
                .compose(h -> {
                    app = h;
                    return h.awaitReady(90_000);
                })
                .compose(v -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (String id : Fixtures.lockedPackages()) {
                        chain = chain.compose(x -> TestPackages.storePackage(app.bootstrap().services(), Fixtures.lockedPackage(id)));
                    }
                    return chain;
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        if (app != null) {
            app.bootstrap().services().heapBudget().setMaxHeap(() -> Runtime.getRuntime().maxMemory());
        }
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<HttpResponse<Buffer>> include(String id) {
        String[] p = id.split("#");
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("igPackageId", p[0]).put("igPackageVersion", p[1]));
    }

    private Future<Long> built(Vertx vertx, String id) {
        return Await.until(vertx, 300_000, id + " is built", () -> app.bootstrap().services().validatorRegistry()
                .snapshot(SnapshotKey.of(R4, id)).map(s -> true).otherwise(false));
    }

    /** Unpacked bytes of MedCom Core's resolved packages: [its own, shared with DK Core]. */
    private static long[] medcomExpandedBytes() throws Exception {
        Map<String, NpmPackage> parsed = new HashMap<>();
        Map<String, List<String>> deps = new HashMap<>();
        for (String id : Fixtures.lockedPackages()) {
            NpmPackage pkg = NpmPackage.fromPackage(new ByteArrayInputStream(Fixtures.lockedPackage(id)));
            parsed.put(id, pkg);
            deps.put(id, pkg.dependencies());
        }
        DependencyResolver.Catalog catalog = DependencyResolver.Catalog.of(deps);
        List<String> dk = DependencyResolver.resolve(List.of(Fixtures.DK_CORE_350), catalog).resolved();
        long own = 0;
        long shared = 0;
        for (String id : DependencyResolver.resolve(List.of(Fixtures.MEDCOM_CORE_400), catalog).resolved()) {
            long bytes = PostgresPackageCacheManager.expandedBytes(parsed.get(id));
            if (dk.contains(id)) {
                shared += bytes;
            } else {
                own += bytes;
            }
        }
        return new long[]{own, shared};
    }

    @Test
    void aBuildOfSharedPackagesIsAdmittedWhereAFullReparseWouldNotFit(Vertx vertx, VertxTestContext ctx) throws Exception {
        long[] bytes = medcomExpandedBytes();
        long sharedNeed = HeapBudget.estimate(bytes[0]) + HeapBudget.sharedBytes(bytes[1]);
        long fullNeed = HeapBudget.estimate(bytes[0] + bytes[1]);
        assertTrue(fullNeed - sharedNeed > 100L * 1024 * 1024, "the test needs a clear gap: " + fullNeed + " vs " + sharedNeed);
        HeapBudget heap = app.bootstrap().services().heapBudget();
        include(Fixtures.DK_CORE_350)
                .compose(r -> built(vertx, Fixtures.DK_CORE_350))
                .compose(ms -> {
                    System.gc();
                    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    // Halfway between what the shared build needs and what a full reparse would need.
                    long limit = used + heap.reservedBytes() + (sharedNeed + fullNeed) / 2;
                    heap.setMaxHeap(() -> limit);
                    return include(Fixtures.MEDCOM_CORE_400);
                })
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), "admitted (US4-1): " + r.bodyAsString()));
                    return built(vertx, Fixtures.MEDCOM_CORE_400);
                })
                .compose(ms -> app.client().get(app.port(), "localhost", "/metrics").send())
                .onComplete(ctx.succeeding(m -> ctx.verify(() -> {
                    String body = m.bodyAsString();
                    assertTrue(body.lines().anyMatch(l -> l.startsWith("fv_packages_shared_lookups_total{")
                            && l.contains("result=\"hit\"") && !l.endsWith(" 0.0")), "a reuse is counted (US4-2)");
                    assertTrue(body.contains("fv_packages_shared_entries{"), "entries gauge");
                    assertTrue(body.contains("fv_packages_shared_retained_estimate_bytes{"), "retained estimate gauge");
                    assertTrue(body.contains("fv_tx_code_cache_size"), "code cache size gauge");
                    ctx.completeNow();
                })));
    }
}
