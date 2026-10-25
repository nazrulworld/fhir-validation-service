package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7/B8 regression: "latest" means the highest released version, and long version labels can be stored.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class PackageLatestResolutionIT extends BaseTestContainer {

    private CoreHarness core;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        CoreHarness.start(vertx, connectOptions()).onComplete(ctx.succeeding(h -> {
            core = h;
            ctx.completeNow();
        }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<Void> store(String name, String version) {
        return core.services().igPackageService().registerIg(TestPackages.npmPackage(name, version, Map.of(), List.of())).mapEmpty();
    }

    @Test
    void latestIsHighestReleaseRegardlessOfUploadOrder(VertxTestContext ctx) {
        Pool pool = core.services().pool();
        store("example.latest", "2.0.0")
                .compose(v -> store("example.latest", "1.5.0"))
                .compose(v -> store("example.latest", "2.1.0-ballot"))
                .compose(v -> PostgresPackageCacheManager.getLatestVersionFromCache(pool, "example.latest"))
                .compose(latest -> {
                    ctx.verify(() -> assertEquals("2.0.0", latest));
                    return core.services().igPackageService().include(SupportedFhirVersion.R4, "example.latest", "latest");
                })
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertTrue(r.getJsonArray("resolved").contains("example.latest#2.0.0"), r.encode());
                    ctx.completeNow();
                })));
    }

    @Test
    void longVersionLabelsRoundTrip(VertxTestContext ctx) {
        String sixtyFour = "1.0.0-" + "a".repeat(58);
        assertEquals(64, sixtyFour.length());
        store("example.long", "4.0.0-ballot-2023")
                .compose(v -> store("example.long", sixtyFour))
                .compose(v -> core.services().igPackageService().cacheManager().isPackageExists("example.long", sixtyFour))
                .compose(exists -> {
                    ctx.verify(() -> assertTrue(exists));
                    return core.services().igPackageService().cacheManager().isPackageExists("example.long", "4.0.0-ballot-2023");
                })
                .onComplete(ctx.succeeding(exists -> ctx.verify(() -> {
                    assertTrue(exists);
                    ctx.completeNow();
                })));
    }

    @Test
    void versionLabelLongerThan64IsRejected(VertxTestContext ctx) {
        store("example.toolong", "1.0.0-" + "a".repeat(59))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(400, ((ProblemException) err).status());
                    ctx.completeNow();
                })));
    }
}
