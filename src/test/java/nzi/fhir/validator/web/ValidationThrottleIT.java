package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.throttle.ThrottleService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertDeclaredBySchema;
import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 014: rate limits on {@code POST /{version}/validate}, end to end on one instance (US1, US2, US3 AC3, US5,
 * FR-010a, FR-018a, readiness). Caps are written with SQL here; the {@code limit} command line has its own IT.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ValidationThrottleIT extends BaseTestContainer {
    static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";
    private static final String S = "fhir_validator_schema.";
    private static final long T0 = 1_800_000_000_000L;

    private Vertx vertx;
    private AppHarness app;
    private final AtomicLong clock = new AtomicLong(T0);

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME,
                POSTGRES_PASSWORD);
        config.put("usage", new JsonObject().put("sink", "db").put("flushMs", 100));
        config.put("throttle", new JsonObject().put("capsRefreshMs", 1000));
        config.put("http", config.getJsonObject("http").put("cors", new JsonObject().put("allowedOrigins", "https://app.example")));
        app = await(AppHarness.start(vertx, config));
        await(app.awaitReady(90_000));
        app.withThrottleClock(clock);
    }

    @AfterEach
    void stop() {
        if (app != null) {
            await(app.stop());
        }
        await(vertx.close());
    }

    static <T> T await(Future<T> f) {
        try {
            return f.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- fixtures ----

    UUID org(String shortName, Integer cap) {
        UUID id = UUID.randomUUID();
        await(app.bootstrap().services().pool().preparedQuery("INSERT INTO " + S + "organisations (id, name_short, name_long, "
                + "type, rate_limit_per_minute) VALUES ($1, $2, $2, 'COMMERCIAL', $3)").execute(Tuple.of(id, shortName, cap)));
        return id;
    }

    /** A key of {@code org}; returns its id. The key value is {@code name} padded to a well-formed key. */
    UUID key(String name, ApiKeyRepository.Role role, UUID org, Integer cap) {
        await(app.bootstrap().services().apiKeys().create(name, keyValue(name), role, org));
        return await(app.bootstrap().services().pool().preparedQuery("UPDATE " + S + "api_keys SET rate_limit_per_minute = $1 "
                + "WHERE name = $2 RETURNING id").execute(Tuple.of(cap, name))).iterator().next().getUUID(0);
    }

    static String keyValue(String name) {
        return (name + "-0123456789abcdefghijklmnopqrstuvwxyz").substring(0, 40);
    }

    ThrottleService throttle() {
        return app.bootstrap().services().throttle().orElseThrow();
    }

    /** Waits until the running service has loaded caps that satisfy {@code condition} (FR-014). */
    void awaitCaps(java.util.function.Predicate<nzi.fhir.validator.core.throttle.ThrottleCaps> condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.test(throttle().caps())) {
            assertTrue(System.currentTimeMillis() < deadline, "caps not reloaded in time: " + throttle().caps());
            sleep(100);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    HttpResponse<Buffer> validate(String key, String body) {
        return await(app.client().post(app.port(), "localhost", "/R4/validate").timeout(30_000)
                .putHeader("Authorization", "Bearer " + key).putHeader("Content-Type", "application/fhir+json")
                .putHeader("Origin", "https://app.example").sendBuffer(Buffer.buffer(body)));
    }

    HttpResponse<Buffer> validate(String key) {
        return validate(key, PATIENT);
    }

    long usageRows(UUID keyId, int status) {
        return await(app.bootstrap().services().pool().preparedQuery("SELECT count(*) FROM " + S + "validation_usage "
                + "WHERE api_key_id = $1 AND http_status = $2").execute(Tuple.of(keyId, status))).iterator().next().getLong(0);
    }

    static void assertLimitHeaders(HttpResponse<Buffer> r, int limit, int remaining) {
        assertEquals(String.valueOf(limit), r.getHeader("RateLimit-Limit"), r.bodyAsString());
        assertEquals(String.valueOf(remaining), r.getHeader("RateLimit-Remaining"));
        assertNotNull(r.getHeader("RateLimit-Reset"));
    }

    static void assertNoLimitHeaders(HttpResponse<Buffer> r) {
        assertNull(r.getHeader("RateLimit-Limit"));
        assertNull(r.getHeader("RateLimit-Remaining"));
        assertNull(r.getHeader("RateLimit-Reset"));
    }

    static JsonObject assertRateLimited(HttpResponse<Buffer> r, String scope, int limit) {
        JsonObject body = assertProblem(r, 429, "rate-limited");
        assertDeclaredBySchema(body, "Problem");
        assertEquals(scope, body.getString("scope"));
        assertEquals(limit, body.getInteger("limit"));
        assertEquals(r.getHeader("Retry-After"), String.valueOf(body.getInteger("retryAfterSeconds")));
        String who = scope.equals("key") ? "This API key's" : "This organisation's";
        assertEquals(who + " rate limit of " + limit + " validation requests per minute was reached; retry after "
                + body.getInteger("retryAfterSeconds") + " s", body.getString("detail"));
        return body;
    }

    // ---- US1 ----

    /** US1 AC1–AC3, AC5; FR-007, FR-008, FR-009, FR-010a, FR-018a; CORS. */
    @Test
    void keyCap() {
        UUID acme = org("acme", null);
        UUID keyA = key("acme-a", ApiKeyRepository.Role.VALIDATE, acme, 5);
        awaitCaps(c -> c.keyCap(keyA).orElse(0) == 5);
        String a = keyValue("acme-a");
        for (int i = 0; i < 5; i++) {
            HttpResponse<Buffer> ok = validate(a);
            assertEquals(200, ok.statusCode(), ok.bodyAsString());
            assertLimitHeaders(ok, 5, 4 - i);
            assertTrue(ok.getHeader("Access-Control-Expose-Headers").contains("RateLimit-Remaining"),
                    ok.getHeader("Access-Control-Expose-Headers"));
        }
        HttpResponse<Buffer> refused = validate(a);
        JsonObject body = assertRateLimited(refused, "key", 5);
        assertLimitHeaders(refused, 5, 0);
        int retryAfter = body.getInteger("retryAfterSeconds");
        assertTrue(retryAfter >= 1 && retryAfter <= 61, "retry after " + retryAfter);

        // FR-007: refused before contract validation and parsing; a refusal counts nothing (AC5).
        assertRateLimited(validate(a, "this is not FHIR"), "key", 5);
        for (int i = 0; i < 3; i++) {
            assertEquals(429, validate(a).statusCode());
        }

        // AC3: after the advised wait, served again.
        clock.addAndGet(retryAfter * 1000L);
        HttpResponse<Buffer> again = validate(a);
        assertEquals(200, again.statusCode(), again.bodyAsString());

        // FR-018a: the admitted requests are usage-recorded, the refusals are not.
        long deadline = System.currentTimeMillis() + 10_000;
        while (usageRows(keyA, 200) < 6) {
            assertTrue(System.currentTimeMillis() < deadline, "usage rows of the admitted requests");
            sleep(100);
        }
        assertEquals(0, usageRows(keyA, 429));
    }

    /** US1 AC4 and FR-010a: another organisation's key is unaffected; a 4xx of a limited key carries the headers. */
    @Test
    void otherKeysAreUnaffectedAndErrorsCarryTheHeaders() {
        UUID acme = org("acme", null);
        UUID beta = org("beta", null);
        UUID keyA = key("acme-a", ApiKeyRepository.Role.VALIDATE, acme, 1);
        UUID keyB = key("beta-b", ApiKeyRepository.Role.VALIDATE, beta, 3);
        awaitCaps(c -> c.keyCap(keyA).orElse(0) == 1 && c.keyCap(keyB).orElse(0) == 3);
        assertEquals(200, validate(keyValue("acme-a")).statusCode());
        assertEquals(429, validate(keyValue("acme-a")).statusCode());
        HttpResponse<Buffer> b = validate(keyValue("beta-b"));
        assertEquals(200, b.statusCode());
        assertLimitHeaders(b, 3, 2);
        HttpResponse<Buffer> invalid = validate(keyValue("beta-b"), "{\"resourceType\":\"NoSuchType\"}");
        assertEquals(400, invalid.statusCode(), invalid.bodyAsString());
        assertLimitHeaders(invalid, 3, 1);
    }

    /** Constitution VII (analysis C2): readiness reports the rate limits until the first cap snapshot is loaded. */
    @Test
    void readinessWaitsForTheCapSnapshot() {
        ThrottleService unstarted = new ThrottleService(vertx, app.bootstrap().services().pool(), app.bootstrap().services().config());
        assertFalse(unstarted.ready());
        assertEquals(java.util.List.of("x", ThrottleService.NOT_LOADED), unstarted.notReadyReasons(java.util.List.of("x")));
        assertTrue(throttle().ready());
        assertEquals(java.util.List.of(), throttle().notReadyReasons(java.util.List.of()));
        // Code review of #99: start() completes once the caps are loaded; the instance then warms up (two sync ticks)
        // with its own readiness reason, and is ready afterwards.
        await(unstarted.start());
        try {
            assertFalse(unstarted.ready());
            assertEquals(java.util.List.of(ThrottleService.WARMING_UP), unstarted.notReadyReasons(java.util.List.of()));
            await(unstarted.warmed());
            assertTrue(unstarted.ready());
            assertEquals(java.util.List.of(), unstarted.notReadyReasons(java.util.List.of()));
        } finally {
            await(unstarted.stop());
        }
        HttpResponse<Buffer> ready = await(app.client().get(app.port(), "localhost", "/health/readiness").send());
        assertEquals(200, ready.statusCode(), ready.bodyAsString());
        assertFalse(ready.bodyAsString().contains("rate limits not loaded"));
    }

    // ---- US2 ----

    /** US2 AC1, AC2: the organisation's keys together get exactly its cap; the refusal names no other key. */
    @Test
    void organisationCap() {
        UUID acme = org("acme", 6);
        UUID keyA = key("acme-a", ApiKeyRepository.Role.VALIDATE, acme, 5);
        UUID keyB = key("acme-b", ApiKeyRepository.Role.VALIDATE, acme, 5);
        awaitCaps(c -> c.orgCap(acme).orElse(0) == 6 && c.keyCap(keyA).orElse(0) == 5 && c.keyCap(keyB).orElse(0) == 5);
        int ok = 0;
        java.util.List<HttpResponse<Buffer>> refused = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (String k : new String[]{"acme-a", "acme-b"}) {
                HttpResponse<Buffer> r = validate(keyValue(k));
                if (r.statusCode() == 200) {
                    ok++;
                } else {
                    refused.add(r);
                }
            }
        }
        assertEquals(6, ok);
        assertEquals(2, refused.size());
        for (HttpResponse<Buffer> r : refused) {
            assertRateLimited(r, "organisation", 6);
            String text = r.bodyAsString();
            for (String other : new String[]{keyA.toString(), keyB.toString(), "acme-a", "acme-b", acme.toString()}) {
                assertFalse(text.contains(other), "the refusal names " + other + ": " + text);
            }
        }
    }

    // ---- US3 ----

    /** US3 AC3, FR-014: a cap change reaches the running instance without a restart (refresh every 1 s here). */
    @Test
    void capChangesApplyWithoutARestart() {
        UUID acme = org("acme", null);
        UUID keyA = key("acme-a", ApiKeyRepository.Role.VALIDATE, acme, 1);
        awaitCaps(c -> c.keyCap(keyA).orElse(0) == 1);
        assertEquals(200, validate(keyValue("acme-a")).statusCode());
        assertEquals(429, validate(keyValue("acme-a")).statusCode());
        await(app.bootstrap().services().pool().preparedQuery("UPDATE " + S + "api_keys SET rate_limit_per_minute = 3 "
                + "WHERE id = $1").execute(Tuple.of(keyA)));
        long changed = System.currentTimeMillis();
        HttpResponse<Buffer> r;
        do {
            assertTrue(System.currentTimeMillis() - changed < 2_500, "the new cap is applied within about 2 s");
            sleep(100);
            r = validate(keyValue("acme-a"));
        } while (r.statusCode() == 429);
        assertEquals(200, r.statusCode(), r.bodyAsString());
        assertLimitHeaders(r, 3, 1);
    }

    // ---- US5 ----

    /** US5 AC1, AC2; FR-006a, FR-006b: system-organisation keys are never limited; other ADMIN keys are. */
    @Test
    void systemOrganisationKeysAreNotThrottled() {
        await(app.bootstrap().services().pool().query("UPDATE " + S + "service_settings SET value = '1' "
                + "WHERE name IN ('throttle_default_key_per_minute', 'throttle_default_org_per_minute')").execute());
        key("sys-validate", ApiKeyRepository.Role.VALIDATE, Constant.SYSTEM_ORG_UUID, null);
        UUID acme = org("acme", 10);
        UUID adminA = key("acme-admin", ApiKeyRepository.Role.ADMIN, acme, 1);
        awaitCaps(c -> c.defaultKey().orElse(0) == 1 && c.keyCap(adminA).orElse(0) == 1);
        for (String k : new String[]{AppHarness.ADMIN_KEY, keyValue("sys-validate")}) {
            for (int i = 0; i < 20; i++) {
                HttpResponse<Buffer> r = validate(k);
                assertEquals(200, r.statusCode(), r.bodyAsString());
                assertNoLimitHeaders(r);
            }
        }
        assertEquals(200, validate(keyValue("acme-admin")).statusCode());
        assertRateLimited(validate(keyValue("acme-admin")), "key", 1);
    }

    // ---- observability ----

    /** FR-017, FR-018: the refusal metric and the bounded refusal log (at most one line per key per minute). */
    @Test
    void refusalMetricAndBoundedLog() {
        UUID acme = org("acme", null);
        UUID keyA = key("acme-a", ApiKeyRepository.Role.VALIDATE, acme, 1);
        awaitCaps(c -> c.keyCap(keyA).orElse(0) == 1);
        String a = keyValue("acme-a");
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            assertEquals(200, validate(a).statusCode());
            HttpResponse<Buffer> first = validate(a);
            assertEquals(429, first.statusCode());
            java.util.function.Supplier<java.util.List<String>> lines = () -> logs.events().stream()
                    .map(nzi.fhir.validator.testsupport.LogCapture.Captured::message)
                    .filter(m -> m.startsWith("throttle: key ")).distinct().toList();
            assertEquals(java.util.List.of("throttle: key " + keyA + " (org " + acme + ") refused 1 request(s) in the last "
                    + "minute (cap 1); request " + first.getHeader("X-Request-Id")), lines.get());
            for (int i = 0; i < 100; i++) {
                assertEquals(429, validate(a).statusCode());
            }
            assertEquals(1, lines.get().size(), "no line for refusals within the minute");
            clock.addAndGet(60_000);
            assertEquals(429, validate(a).statusCode(), "the admitted request of T0 is still in the window");
            java.util.List<String> after = lines.get();
            assertEquals(2, after.size(), after.toString());
            assertTrue(after.get(1).contains(" refused 101 request(s) in the last minute (cap 1); request "), after.get(1));
            for (String line : after) {
                assertFalse(line.contains(a.substring(0, 12)), "no key value in the log: " + line);
            }
        }
        String scrape = await(app.client().get(app.port(), "localhost", "/metrics").send()).bodyAsString();
        assertTrue(scrape.contains("fv_throttle_refused_total{reason=\"cap\",scope=\"key\",} 102.0")
                || scrape.contains("fv_throttle_refused_total{reason=\"cap\",scope=\"key\"} 102.0"), scrape.lines()
                .filter(l -> l.startsWith("fv_throttle")).toList().toString());
        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("fv_throttle_checked_total")));
        for (String name : new String[]{"fv_throttle_tracked_subjects", "fv_throttle_peers", "fv_throttle_local_mode",
                "fv_throttle_evictions_total", "fv_throttle_sync_seconds_count", "fv_throttle_sync_failures_total",
                "fv_throttle_ticks_skipped_total"}) {
            assertTrue(scrape.lines().anyMatch(l -> l.startsWith(name)), name);
        }
        scrape.lines().filter(l -> l.startsWith("fv_throttle")).forEach(l -> {
            assertFalse(l.contains(keyA.toString()) || l.contains(acme.toString()), "no subject id in metrics: " + l);
        });
    }
}
