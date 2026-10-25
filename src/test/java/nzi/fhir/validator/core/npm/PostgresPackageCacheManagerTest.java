package nzi.fhir.validator.core.npm;

import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class PostgresPackageCacheManagerTest extends BaseTestContainer {
    private static final String PACKAGE_ID = "hl7.fhir.us.core";
    private static final String PACKAGE_VERSION = "7.0.0";

    private Pool pool;
    private PostgresPackageCacheManager manager;

    @BeforeEach
    void setUp(Vertx vertx) {
        resetSchema();
        pool = createPgPool(vertx);
        createTables(pool);
        manager = new PostgresPackageCacheManager(vertx, pool);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        pool.close().onComplete(ar -> ctx.completeNow());
    }

    private static InputStream usCore() {
        return new ByteArrayInputStream(Fixtures.bytes(Fixtures.US_CORE_IG));
    }

    @Test
    void nullInputStreamFails(VertxTestContext ctx) {
        manager.addPackageToCache(null).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            ctx.completeNow();
        })));
    }

    @Test
    void storeAndLoad(VertxTestContext ctx) {
        manager.addPackageToCache(usCore())
                .compose(stored -> manager.loadPackage(PACKAGE_ID + "#" + PACKAGE_VERSION))
                .onComplete(ctx.succeeding(pkg -> ctx.verify(() -> {
                    assertEquals(PACKAGE_ID, pkg.name());
                    assertEquals(PACKAGE_VERSION, pkg.version());
                    ctx.completeNow();
                })));
    }

    @Test
    void existsAndRemove(VertxTestContext ctx) {
        manager.addPackageToCache(usCore())
                .compose(p -> manager.isPackageExists(PACKAGE_ID, PACKAGE_VERSION))
                .compose(exists -> {
                    ctx.verify(() -> assertTrue(exists));
                    return manager.removePackage(PACKAGE_ID, PACKAGE_VERSION);
                })
                .compose(v -> manager.isPackageExists(PACKAGE_ID, PACKAGE_VERSION))
                .onComplete(ctx.succeeding(exists -> ctx.verify(() -> {
                    assertFalse(exists);
                    ctx.completeNow();
                })));
    }

    @Test
    void latestResolvesToStoredVersion(VertxTestContext ctx) {
        manager.addPackageToCache(usCore())
                .compose(p -> manager.resolveVersion(PACKAGE_ID, "latest", true))
                .onComplete(ctx.succeeding(version -> ctx.verify(() -> {
                    assertEquals(PACKAGE_VERSION, version);
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownPackageIsNullWhenRegistryIsDisabled(VertxTestContext ctx) {
        manager.loadPackage("no.such.package#1.0.0", false, false).onComplete(ctx.succeeding(pkg -> ctx.verify(() -> {
            assertNull(pkg);
            ctx.completeNow();
        })));
    }

    @Test
    void storedRowRecordsProvenance(VertxTestContext ctx) {
        manager.store(Fixtures.bytes(Fixtures.DK_CORE_IG), "URL")
                .compose(p -> pool.query("SELECT source, length(content_sha256), updated_at IS NOT NULL FROM fhir_validator_schema.fhir_implementation_guides").execute())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals("URL", row.getString(0));
                    assertEquals(32, row.getInteger(1));
                    assertTrue(row.getBoolean(2));
                    ctx.completeNow();
                })));
    }
}
