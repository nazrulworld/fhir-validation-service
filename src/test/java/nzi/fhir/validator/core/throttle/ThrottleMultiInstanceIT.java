package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 014 (T040, SC-001, FR-005, FR-019): several {@link ThrottleService}s sharing counts through one real PostgreSQL,
 * with {@code FV_THROTTLE_SYNC_INTERVAL_MS=200}.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ThrottleMultiInstanceIT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema.";

    private Vertx vertx;
    private Pool pool;
    private final List<ThrottleService> services = new ArrayList<>();
    private final List<Pool> pools = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        vertx = Vertx.vertx();
        pool = createPgPool(vertx);
    }

    @AfterEach
    void tearDown() {
        for (ThrottleService s : services) {
            await(s.stop());
        }
        for (Pool p : pools) {
            p.close();
        }
        pool.close();
        await(vertx.close());
    }

    static <T> T await(Future<T> f) {
        try {
            return f.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void defaultKeyCap(int cap) {
        await(pool.preparedQuery("UPDATE " + S + "service_settings SET value = $1 WHERE name = 'throttle_default_key_per_minute'")
                .execute(Tuple.of(String.valueOf(cap))));
        await(pool.query("DELETE FROM " + S + "service_settings WHERE name = 'throttle_default_org_per_minute'").execute());
    }

    private ThrottleService instance() {
        ApplicationConfig config = ApplicationConfig.of(new JsonObject().put("profile", "dev")
                .put("throttle", new JsonObject().put("syncIntervalMs", 200).put("capsRefreshMs", 1000).put("maxTracked", 1000)));
        // Each instance has its own pool, as separate processes would.
        Pool own = createPgPool(vertx);
        pools.add(own);
        ThrottleService s = new ThrottleService(vertx, own, config);
        await(s.start());
        services.add(s);
        return s;
    }

    private static Caller caller(UUID key) {
        return Caller.of(UUID.randomUUID(), "acme", ApiKeyRepository.Role.VALIDATE, key, "k");
    }

    private void awaitPeers(int peers, ThrottleService... all) {
        long deadline = System.currentTimeMillis() + 10_000;
        for (ThrottleService s : all) {
            while (s.throttle().peers() != peers) {
                assertTrue(System.currentTimeMillis() < deadline, "peers = " + s.throttle().peers());
                sleep(50);
            }
        }
    }

    /** SC-001: two instances, cap 30, alternating requests → at most 33 admitted fleet-wide (and the cap is used). */
    @Test
    void twoInstancesStayWithinTheBound() {
        defaultKeyCap(30);
        ThrottleService a = instance();
        ThrottleService b = instance();
        awaitPeers(1, a, b);
        long rows = (Long) await(pool.query("SELECT count(*) FROM " + S + "throttle_instances").execute()).iterator().next().getValue(0);
        assertEquals(2, rows);
        UUID key = UUID.randomUUID();
        Caller c = caller(key);
        int admitted = 0;
        for (int i = 0; i < 150; i++) {
            if ((i % 2 == 0 ? a : b).check(c).admitted()) {
                admitted++;
            }
            sleep(20);
        }
        assertTrue(admitted <= 33, "admitted " + admitted);
        assertTrue(admitted >= 30, "the cap is used: " + admitted);

        // A third instance started now sees the fleet's counts from its start-up read of the last 60 s.
        ThrottleService late = instance();
        long nowSec = late.now() / 1000;
        assertTrue(late.throttle().window(Throttle.KEY, key).count(nowSec) >= admitted - 1,
                "late instance sees " + late.throttle().window(Throttle.KEY, key).count(nowSec));
        int lateAdmitted = 0;
        for (int i = 0; i < 10; i++) {
            if (late.check(c).admitted()) {
                lateAdmitted++;
            }
        }
        assertTrue(admitted + lateAdmitted <= 33, "fleet total " + (admitted + lateAdmitted) + " with the late instance");
    }

    /** Research R4 / analysis I2: a burst-guard refusal's Retry-After (1 s at 200 ms) is enough for the retry. */
    @Test
    void unconfirmedRefusalRetryAfterIsEnough() {
        defaultKeyCap(10); // B = 1, one peer: r = 1
        ThrottleService a = instance();
        ThrottleService b = instance();
        awaitPeers(1, a, b);
        Caller c = caller(UUID.randomUUID());
        assertTrue(a.check(c).admitted());
        ThrottleDecision refused = a.check(c);
        assertFalse(refused.admitted());
        assertEquals(ThrottleDecision.Reason.UNCONFIRMED, refused.reason());
        assertEquals(1, refused.retryAfterSeconds());
        sleep(refused.retryAfterSeconds() * 1000L + 50);
        assertTrue(a.check(c).admitted(), "admitted after the advised wait");
    }

    /** FR-019: while the database doesn't answer, each instance enforces caps on its own counts and refuses nothing more. */
    @Test
    void localModeWhileTheDatabaseIsUnreachable() {
        defaultKeyCap(5);
        ThrottleService a = instance();
        ThrottleService b = instance();
        awaitPeers(1, a, b);
        try (LogCapture logs = LogCapture.start()) {
            String id = postgresContainer.getContainerId();
            postgresContainer.getDockerClient().pauseContainerCmd(id).exec();
            try {
                long deadline = System.currentTimeMillis() + 5_000;
                while (!(a.sync().localMode() && b.sync().localMode())) {
                    assertTrue(System.currentTimeMillis() < deadline, "local mode");
                    sleep(50);
                }
                Caller c = caller(UUID.randomUUID());
                int admittedA = 0;
                for (int i = 0; i < 10; i++) {
                    if (a.check(c).admitted()) {
                        admittedA++;
                    }
                }
                assertEquals(5, admittedA, "the full cap on this instance's own counts, nothing refused for sharing");
                assertEquals(0, a.throttle().peers());
            } finally {
                postgresContainer.getDockerClient().unpauseContainerCmd(id).exec();
            }
            long deadline = System.currentTimeMillis() + 30_000;
            while (a.sync().localMode() || b.sync().localMode()) {
                assertTrue(System.currentTimeMillis() < deadline, "back to shared counts");
                sleep(100);
            }
            List<String> messages = logs.events().stream().map(LogCapture.Captured::message).distinct().toList();
            assertTrue(messages.stream().anyMatch(m -> m.startsWith("throttle: cannot share request counts (")
                    && m.endsWith("); enforcing caps on this instance's counts only")), messages.toString());
            assertTrue(messages.stream().anyMatch(m -> m.matches("throttle: request counts shared again after \\d+ s")),
                    messages.toString());
        }
    }

    /** Research R4: rows older than 120 s are deleted; a graceful stop removes the heartbeat. */
    @Test
    void cleanupAndGracefulStop() {
        ThrottleCountsRepository store = new ThrottleCountsRepository(pool);
        long nowSec = System.currentTimeMillis() / 1000;
        UUID instance = UUID.randomUUID();
        await(store.flush(instance, List.of(new CountStore.CountRow('K', UUID.randomUUID(), nowSec - 200, 3),
                new CountStore.CountRow('K', UUID.randomUUID(), nowSec - 10, 2))));
        await(store.cleanup(nowSec - ThrottleSync.KEEP_SECONDS));
        assertEquals(1L, await(pool.query("SELECT count(*) FROM " + S + "throttle_counts").execute()).iterator().next().getLong(0));

        ThrottleService a = instance();
        UUID id = a.sync().instanceId();
        assertEquals(1L, await(pool.preparedQuery("SELECT count(*) FROM " + S + "throttle_instances WHERE instance_id = $1")
                .execute(Tuple.of(id))).iterator().next().getLong(0));
        await(a.stop());
        services.remove(a);
        assertEquals(0L, await(pool.preparedQuery("SELECT count(*) FROM " + S + "throttle_instances WHERE instance_id = $1")
                .execute(Tuple.of(id))).iterator().next().getLong(0));
    }
}
