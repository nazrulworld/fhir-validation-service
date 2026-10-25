package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B9/B19/P6 regression: several registries, local stand-ins.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class RegistryFallbackTest extends BaseTestContainer {
    private static final byte[] PACKAGE = TestPackages.npmPackage("example.remote", "1.2.0", Map.of(), List.of());

    private Pool pool;
    private WorkerExecutor workers;
    private HttpServer fast404;
    private HttpServer slowHas;
    private HttpServer hangs;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        pool = createPgPool(vertx);
        createTables(pool);
        workers = vertx.createSharedWorkerExecutor("registry-test", 2);
        fast404 = vertx.createHttpServer().requestHandler(req -> req.response().setStatusCode(404).end());
        slowHas = vertx.createHttpServer().requestHandler(req -> vertx.setTimer(300, id -> {
            hits.incrementAndGet();
            if (req.path().equals("/example.remote/1.2.0")) {
                req.response().end(Buffer.buffer(PACKAGE));
            } else if (req.path().equals("/example.remote")) {
                req.response().end(new JsonObject().put("dist-tags", new JsonObject().put("latest", "1.2.0")).encode());
            } else {
                req.response().setStatusCode(404).end();
            }
        }));
        hangs = vertx.createHttpServer().requestHandler(req -> { /* never answers */ });
        Future.all(fast404.listen(0), slowHas.listen(0), hangs.listen(0)).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future.join(fast404.close(), slowHas.close(), hangs.close(), pool.close()).onComplete(ar -> ctx.completeNow());
    }

    private PostgresPackageCacheManager manager(Vertx vertx, long timeoutMs, HttpServer... registries) {
        SafePackageFetcher fetcher = new SafePackageFetcher(vertx, workers, List.of("localhost"), timeoutMs,
                10 * 1024 * 1024, true, host -> List.of(InetAddress.getLoopbackAddress()));
        List<String> urls = java.util.Arrays.stream(registries).map(s -> "http://localhost:" + s.actualPort()).toList();
        return new PostgresPackageCacheManager(pool, workers, PackageArchiveGuard.defaults(), fetcher, urls, true);
    }

    @Test
    void slowerRegistryWithThePackageWinsOverFast404(Vertx vertx, VertxTestContext ctx) {
        PostgresPackageCacheManager m = manager(vertx, 5_000, fast404, slowHas);
        m.loadPackage("example.remote#1.2.0", false, false)
                .compose(pkg -> {
                    ctx.verify(() -> assertEquals("1.2.0", pkg.version()));
                    return pool.query("SELECT count(*) FROM fhir_validator_schema.fhir_implementation_guides").execute();
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1L, rows.iterator().next().getLong(0), "stored once");
                    ctx.completeNow();
                })));
    }

    @Test
    void notFoundOnlyWhenNoRegistryHasIt(Vertx vertx, VertxTestContext ctx) {
        manager(vertx, 5_000, fast404, slowHas).loadPackage("example.absent#9.9.9", false, false)
                .onComplete(ctx.succeeding(pkg -> ctx.verify(() -> {
                    assertNull(pkg);
                    ctx.completeNow();
                })));
    }

    @Test
    void hangingRegistryTimesOutAndOthersAreUsed(Vertx vertx, VertxTestContext ctx) {
        long start = System.currentTimeMillis();
        manager(vertx, 1_000, hangs, slowHas).loadPackage("example.remote#1.2.0", false, false)
                .onComplete(ctx.succeeding(pkg -> ctx.verify(() -> {
                    assertEquals("1.2.0", pkg.version());
                    assertTrue(System.currentTimeMillis() - start < 5_000);
                    ctx.completeNow();
                })));
    }

    @Test
    void latestLookupIgnoresUnreachableRegistries(Vertx vertx, VertxTestContext ctx) {
        manager(vertx, 1_000, hangs, fast404, slowHas).resolveVersion("example.remote", "latest", false)
                .onComplete(ctx.succeeding(version -> ctx.verify(() -> {
                    assertEquals("1.2.0", version);
                    ctx.completeNow();
                })));
    }
}
