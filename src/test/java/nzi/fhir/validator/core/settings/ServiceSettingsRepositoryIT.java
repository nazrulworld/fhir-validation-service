package nzi.fhir.validator.core.settings;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.core.settings.ServiceSettingsRepository.ACL_SECURITY;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 009 (T009): {@code service_settings} reads, writes and row locks. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class ServiceSettingsRepositoryIT extends BaseTestContainer {

    private Pool pool;

    @BeforeEach
    void setUp(Vertx vertx) {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        pool = createPgPool(vertx);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        pool.close().onComplete(ar -> ctx.completeNow());
    }

    @Test
    void freshDatabaseHasNoStoredModeThenUpsertInsertsAndUpdates(VertxTestContext ctx) {
        pool.withTransaction(tx -> ServiceSettingsRepository.lockForUpdate(tx, ACL_SECURITY))
                .compose(none -> {
                    ctx.verify(() -> assertEquals(Optional.empty(), none, "V15 seeds no row"));
                    return pool.withTransaction(tx -> ServiceSettingsRepository.upsert(tx, ACL_SECURITY, "disabled", "host-a"));
                })
                .compose(v -> pool.query("SELECT value, updated_by, updated_at FROM fhir_validator_schema.service_settings").execute())
                .compose(first -> pool.withTransaction(tx -> ServiceSettingsRepository.upsert(tx, ACL_SECURITY, "enabled", "host-b"))
                        .compose(v -> pool.query("SELECT value, updated_by, updated_at FROM fhir_validator_schema.service_settings").execute())
                        .map(second -> {
                            ctx.verify(() -> {
                                assertEquals(1, second.size());
                                var a = first.iterator().next();
                                var b = second.iterator().next();
                                assertEquals("disabled", a.getString(0));
                                assertEquals("host-a", a.getString(1));
                                assertEquals("enabled", b.getString(0));
                                assertEquals("host-b", b.getString(1));
                                assertFalse(b.getOffsetDateTime(2).isBefore(a.getOffsetDateTime(2)));
                            });
                            return null;
                        }))
                .compose(v -> ServiceSettingsRepository.get(pool, ACL_SECURITY))
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                    assertEquals(Optional.of("enabled"), value);
                    ctx.completeNow();
                })));
    }

    @Test
    void insertIfAbsentInsertsOnce(VertxTestContext ctx) {
        pool.withTransaction(tx -> ServiceSettingsRepository.insertIfAbsent(tx, ACL_SECURITY, "enabled"))
                .compose(first -> pool.withTransaction(tx -> ServiceSettingsRepository.insertIfAbsent(tx, ACL_SECURITY, "disabled"))
                        .map(second -> new boolean[]{first, second}))
                .compose(results -> ServiceSettingsRepository.get(pool, ACL_SECURITY).map(v -> {
                    ctx.verify(() -> {
                        assertTrue(results[0]);
                        assertFalse(results[1], "a second insert changes nothing");
                        assertEquals(Optional.of("enabled"), v);
                    });
                    return null;
                }))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** Two transactions on the same row serialise: the second sees the first's committed value once it may lock. */
    @Test
    void concurrentTransactionsSerialiseOnTheRowLock(VertxTestContext ctx) {
        Promise<Void> firstLocked = Promise.promise();
        Promise<Void> releaseFirst = Promise.promise();
        Future<Void> seeded = pool.withTransaction(tx -> ServiceSettingsRepository.upsert(tx, ACL_SECURITY, "enabled", null));
        seeded.compose(v -> {
            Future<Void> first = pool.withTransaction(tx -> ServiceSettingsRepository.lockForUpdate(tx, ACL_SECURITY)
                    .compose(locked -> {
                        firstLocked.complete();
                        return releaseFirst.future();
                    })
                    .compose(v2 -> ServiceSettingsRepository.upsert(tx, ACL_SECURITY, "disabled", "first")));
            Future<Optional<String>> second = firstLocked.future()
                    .compose(v2 -> {
                        Future<Optional<String>> read = pool.withTransaction(tx -> ServiceSettingsRepository.lockForUpdate(tx, ACL_SECURITY));
                        // The second lock waits; let the first commit a moment later.
                        Vertx.currentContext().owner().setTimer(300, id -> releaseFirst.complete());
                        return read;
                    });
            return Future.all(first, second).map(cf -> second.result());
        }).onComplete(ctx.succeeding(value -> ctx.verify(() -> {
            assertEquals(Optional.of("disabled"), value, "the second lock saw the first transaction's committed value");
            ctx.completeNow();
        })));
    }
}
