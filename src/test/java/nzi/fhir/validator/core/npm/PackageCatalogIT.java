package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.pubsub.PgSubscriber;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every package write bumps the shared catalog revision and notifies other instances, in the storing transaction
 * (spec 004, research R4, FR-025).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class PackageCatalogIT extends BaseTestContainer {
    private Pool pool;
    private PgSubscriber subscriber;
    private PostgresPackageCacheManager manager;
    private final List<JsonObject> notifications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        pool = createPgPool(vertx);
        createTables(pool);
        manager = new PostgresPackageCacheManager(vertx, pool);
        subscriber = PgSubscriber.subscriber(vertx, connectOptions());
        subscriber.channel(ConfigRevisionRepository.CHANNEL).handler(p -> notifications.add(new JsonObject(p)));
        subscriber.connect().onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future.join(subscriber.close(), pool.close()).onComplete(ar -> ctx.completeNow());
    }

    private Future<Long> revision() {
        return PackageCatalogView.load(pool).map(PackageCatalogView::revision);
    }

    @Test
    void storeAndReplaceBumpRevisionNotifyAndChangeHash(Vertx vertx, VertxTestContext ctx) {
        byte[] v1 = TestPackages.npmPackage("cat.ig", "1.0.0", Map.of(), List.of());
        byte[] v1Replaced = TestPackages.profilePackage("cat.ig", "1.0.0",
                TestPackages.requiredPatientElements("http://example.org/cat/p", "birthDate"));
        long[] hash = new long[1];
        revision().compose(start -> {
                    assertEquals(1, start);
                    return manager.store(v1, "UPLOAD");
                })
                .compose(p -> PackageCatalogView.load(pool))
                .compose(view -> {
                    assertEquals(2, view.revision());
                    assertNotNull(view.sha256("cat.ig#1.0.0"));
                    hash[0] = java.util.Arrays.hashCode(view.sha256("cat.ig#1.0.0"));
                    return manager.store(v1Replaced, "UPLOAD");
                })
                .compose(p -> PackageCatalogView.load(pool))
                .onComplete(ctx.succeeding(view -> vertx.setTimer(300, t -> ctx.verify(() -> {
                    assertEquals(3, view.revision());
                    assertNotEquals(hash[0], java.util.Arrays.hashCode(view.sha256("cat.ig#1.0.0")), "replaced content, new hash");
                    List<JsonObject> pkg = notifications.stream().filter(n -> "PACKAGE".equals(n.getString("kind"))).toList();
                    assertEquals(2, pkg.size(), pkg.toString());
                    assertEquals("cat.ig#1.0.0", pkg.get(0).getString("key"));
                    assertEquals(2L, pkg.get(0).getLong("revision"));
                    assertEquals(3L, pkg.get(1).getLong("revision"));
                    ctx.completeNow();
                }))));
    }

    @Test
    void failedStoreLeavesRevisionUnchanged(Vertx vertx, VertxTestContext ctx) {
        manager.store("not a package".getBytes(), "UPLOAD")
                .recover(err -> Future.succeededFuture())
                .compose(v -> revision())
                .onComplete(ctx.succeeding(rev -> vertx.setTimer(200, t -> ctx.verify(() -> {
                    assertEquals(1, rev);
                    assertTrue(notifications.isEmpty());
                    ctx.completeNow();
                }))));
    }
}
