package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.RequestIdHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Constitution VI regression: the key lookup cache is bounded, however many distinct tokens callers send.
 */
@ExtendWith(VertxExtension.class)
class ApiKeyAuthHandlerTest {
    private static final int MAX_CACHED = 10;

    @Test
    void manyDistinctInvalidKeysDoNotGrowTheCacheBeyondItsBound(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.post("/R4/validator").handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
            for (int i = 0; i < 5 * MAX_CACHED; i++) {
                calls.add(client.post(server.actualPort(), "localhost", "/R4/validator")
                        .putHeader("Authorization", "Bearer invalid-key-%040d".formatted(i))
                        .send());
            }
            return Future.all(calls).map(cf -> cf.<HttpResponse<Buffer>>list());
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> assertEquals(401, r.statusCode()));
            assertTrue(auth.cachedKeys() <= MAX_CACHED, "cache holds " + auth.cachedKeys() + " entries");
            assertTrue(auth.cachedKeys() > 0, "lookups are still cached");
            ctx.completeNow();
        })));
    }

    /**
     * Spec 005 research R3: every authorised request carries its {@link Caller}; open paths the anonymous one. Spec 008:
     * validation without a key is refused.
     */
    @Test
    void callerIsPutIntoTheContext(Vertx vertx, VertxTestContext ctx) {
        java.util.UUID org = java.util.UUID.randomUUID();
        String key = "acme-key-0123456789abcdefghijklmnopqrstu";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(
                new ApiKeyRepository.KeyInfo(java.util.UUID.randomUUID(), "acme-key", ApiKeyRepository.Role.VALIDATE, org, "acme", true, null))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> echo = rc -> {
            Caller c = ApiKeyAuthHandler.caller(rc);
            rc.response().end(c.anonymous() ? "anonymous" : c.orgId() + "|" + c.orgShortName() + "|" + c.role() + "|" + c.system());
        };
        router.post("/R4/validate").handler(echo);
        router.get("/health").handler(echo);
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return Future.all(
                    client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send(),
                    client.post(server.actualPort(), "localhost", "/R4/validate").send(),
                    client.get(server.actualPort(), "localhost", "/health").send());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            HttpResponse<Buffer> keyed = cf.resultAt(0);
            HttpResponse<Buffer> noKey = cf.resultAt(1);
            HttpResponse<Buffer> open = cf.resultAt(2);
            assertEquals(org + "|acme|VALIDATE|false", keyed.bodyAsString());
            assertEquals(401, noKey.statusCode(), noKey.bodyAsString());
            assertEquals("anonymous", open.bodyAsString());
            ctx.completeNow();
        })));
    }

    /** Spec 005 FR-014: a valid key of a deactivated organisation is refused with 403. */
    @Test
    void keyOfAnInactiveOrganisationIsForbidden(Vertx vertx, VertxTestContext ctx) {
        String key = "acme-key-0123456789abcdefghijklmnopqrstu";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(new ApiKeyRepository.KeyInfo(
                java.util.UUID.randomUUID(), "acme-admin", ApiKeyRepository.Role.ADMIN, java.util.UUID.randomUUID(), "acme", false, null))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);

        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return Future.all(
                    client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send(),
                    client.post(server.actualPort(), "localhost", "/R4/validator").putHeader("Authorization", "Bearer " + key).send(),
                    client.patch(server.actualPort(), "localhost", "/igs/a/1.0.0").putHeader("Authorization", "Bearer " + key).send());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            for (int i = 0; i < 3; i++) {
                HttpResponse<Buffer> r = cf.resultAt(i);
                assertEquals(403, r.statusCode(), r.bodyAsString());
                assertTrue(r.bodyAsString().contains("deactivated"));
            }
            ctx.completeNow();
        })));
    }

    /** Spec 008 FR-001, SC-002: a bearer value that can't be a key is refused without hashing, caching or a lookup. */
    @Test
    void malformedTokenIsRefusedWithoutLookup(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);
        List<String> malformed = List.of("x", "a b", "a".repeat(31), "a".repeat(129), "\u00fc".repeat(40), "abc.def-0123456789abcdefghijklmnopqrstu");

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
            for (String token : malformed) {
                calls.add(client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + token).send());
            }
            return Future.all(calls).map(cf -> cf.<HttpResponse<Buffer>>list());
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> {
                assertEquals(401, r.statusCode(), r.bodyAsString());
                assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
            });
            verifyNoInteractions(repository);
            assertEquals(0, auth.cachedKeys());
            ctx.completeNow();
        })));
    }

    /** A well-formed but unknown key is still looked up (once, then cached). */
    @Test
    void wellFormedUnknownKeyIsLookedUp(Vertx vertx, VertxTestContext ctx) {
        String key = "unknown-key-0123456789abcdefghijklmnopqr";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.empty()));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            return client.post(server.actualPort(), "localhost", "/R4/validate").putHeader("Authorization", "Bearer " + key).send()
                    .compose(first -> client.post(server.actualPort(), "localhost", "/R4/validate")
                            .putHeader("Authorization", "Bearer " + key).send().map(second -> List.of(first, second)));
        }).onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
            responses.forEach(r -> assertEquals(401, r.statusCode()));
            verify(repository, times(1)).findActive(key);
            ctx.completeNow();
        })));
    }

    /** Spec 008 FR-006, SC-005: a cached key is looked up again once it expires, whatever the cache TTL. */
    @Test
    void cacheEntryNeverOutlivesKeyExpiry(Vertx vertx, VertxTestContext ctx) {
        String key = "expiring-key-0123456789abcdefghijklmnopq";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.succeededFuture(Optional.of(new ApiKeyRepository.KeyInfo(
                java.util.UUID.randomUUID(), "expiring", ApiKeyRepository.Role.VALIDATE, java.util.UUID.randomUUID(), "acme",
                true, java.time.Instant.now().plusMillis(150)))));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0).compose(server -> {
            WebClient client = WebClient.create(vertx);
            java.util.function.Supplier<Future<HttpResponse<Buffer>>> call = () -> client.post(server.actualPort(), "localhost",
                    "/R4/validate").putHeader("Authorization", "Bearer " + key).send();
            return call.get().compose(first -> call.get())
                    .compose(cached -> vertx.timer(250).compose(t -> call.get()));
        }).onComplete(ctx.succeeding(r -> ctx.verify(() -> {
            verify(repository, times(2)).findActive(key);
            ctx.completeNow();
        })));
    }

    /** Code review regression: before the migrations ran (missing column), key lookups answer 503, not 500. */
    @Test
    void aSchemaThatIsNotMigratedYetIsNotReady(Vertx vertx, VertxTestContext ctx) {
        String key = "early-key-0123456789abcdefghijklmnopqrst";
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(key)).thenReturn(Future.failedFuture(
                new io.vertx.pgclient.PgException("column k.expires_at does not exist", "ERROR", "42703", null)));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> rc.response().end("ok"));
        ErrorResponder.install(router);

        vertx.createHttpServer().requestHandler(router).listen(0)
                .compose(server -> WebClient.create(vertx).post(server.actualPort(), "localhost", "/R4/validate")
                        .putHeader("Authorization", "Bearer " + key).send())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(503, r.statusCode(), r.bodyAsString());
                    assertNotNull(r.getHeader("Retry-After"));
                    assertFalse(r.bodyAsString().contains("expires_at"), "no internal detail");
                    ctx.completeNow();
                })));
    }

    // ---- Spec 009 (T040, T047): access control disabled ----

    private static final java.util.UUID SYSTEM_ORG = nzi.fhir.validator.core.Constant.SYSTEM_ORG_UUID;
    private static final java.util.UUID TRIAL_ORG = nzi.fhir.validator.core.Constant.TRIAL_ORG_UUID;

    private static ApiKeyRepository.KeyInfo adminInfo(boolean orgActive) {
        return new ApiKeyRepository.KeyInfo(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID,
                "system administrator", ApiKeyRepository.Role.ADMIN, SYSTEM_ORG, "system", orgActive, null);
    }

    private static ApiKeyRepository.KeyInfo validatorInfo() {
        return new ApiKeyRepository.KeyInfo(nzi.fhir.validator.core.Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID,
                "trial validator", ApiKeyRepository.Role.VALIDATE, TRIAL_ORG, "trial", true, null);
    }

    /** A repository whose fixed callers resolve; key lookups by value fail the test if ever made. */
    private static ApiKeyRepository fixedRepository(Optional<ApiKeyRepository.KeyInfo> admin) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findById(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID))
                .thenReturn(Future.succeededFuture(admin));
        when(repository.findById(nzi.fhir.validator.core.Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID))
                .thenReturn(Future.succeededFuture(Optional.of(validatorInfo())));
        return repository;
    }

    private static ApiKeyAuthHandler disabled(ApiKeyRepository repository, CidrList adminCidrs, boolean gateOpen) {
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, adminCidrs, MAX_CACHED);
        auth.configureAcl(AclMode.DISABLED, new FixedCallers(repository, 60_000));
        if (gateOpen) {
            auth.openGate();
        }
        return auth;
    }

    private static Future<io.vertx.core.http.HttpServer> echoServer(Vertx vertx, ApiKeyAuthHandler auth) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestIdHandler());
        router.route().handler(auth.rootHandler());
        router.route().handler(rc -> {
            Caller c = ApiKeyAuthHandler.caller(rc);
            rc.response().end(c.anonymous() ? "anonymous" : c.apiKeyName() + "|" + c.orgShortName() + "|" + c.role()
                    + "|" + rc.get(ApiKeyAuthHandler.CONTEXT_KEY));
        });
        ErrorResponder.install(router);
        return vertx.createHttpServer().requestHandler(router).listen(0);
    }

    /** T047, FR-017, FR-018: each path runs as its fixed caller, whatever the Authorization header holds. */
    @Test
    void disabledModeRunsEveryKeyedPathAsItsFixedCaller(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = fixedRepository(Optional.of(adminInfo(true)));
        ApiKeyAuthHandler auth = disabled(repository, CidrList.EMPTY, true);
        echoServer(vertx, auth).compose(server -> {
            WebClient c = WebClient.create(vertx);
            int port = server.actualPort();
            return Future.all(List.of(
                    c.post(port, "localhost", "/R4/validator").send(),
                    c.post(port, "localhost", "/R4/validator").putHeader("Authorization", "Bearer garbage").send(),
                    c.post(port, "localhost", "/R4/validate").putHeader("Authorization", "Basic abc").send(),
                    c.get(port, "localhost", "/igs/x/1.0.0/dependencies").send(),
                    c.get(port, "localhost", "/igs/x/1.0.0/conformance").send(),
                    c.get(port, "localhost", "/health").send()));
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            List<String> bodies = cf.<HttpResponse<Buffer>>list().stream().map(HttpResponse::bodyAsString).toList();
            String admin = "system administrator|system|ADMIN|ADMIN";
            String trial = "trial validator|trial|VALIDATE|VALIDATE";
            // Package reads run as the trial validator too (code review): never with the system organisation's view.
            assertEquals(List.of(admin, admin, trial, trial, trial, "anonymous"), bodies);
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findActive(anyString());
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).touch(
                    org.mockito.ArgumentMatchers.eq(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID),
                    org.mockito.ArgumentMatchers.any());
            ctx.completeNow();
        })));
    }

    /** T047, FR-017: the header is never even read in disabled mode. */
    @Test
    void disabledModeNeverReadsTheAuthorizationHeader(VertxTestContext ctx) {
        ApiKeyRepository repository = fixedRepository(Optional.of(adminInfo(true)));
        ApiKeyAuthHandler auth = disabled(repository, CidrList.EMPTY, true);
        io.vertx.ext.web.RoutingContext rc = mock(io.vertx.ext.web.RoutingContext.class);
        io.vertx.core.http.HttpServerRequest request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(rc.request()).thenReturn(request);
        when(request.method()).thenReturn(io.vertx.core.http.HttpMethod.POST);
        when(rc.normalizedPath()).thenReturn("/R4/validator");
        org.mockito.Mockito.doAnswer(inv -> {
            ctx.verify(() -> {
                org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader(anyString());
                org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getHeader(
                        org.mockito.ArgumentMatchers.any(CharSequence.class));
                org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).headers();
            });
            ctx.completeNow();
            return null;
        }).when(rc).next();
        auth.rootHandler().handle(rc);
    }

    /** T040, research R10: until the startup access-control step commits, disabled mode answers keyed paths 503. */
    @Test
    void disabledModeAnswers503UntilTheGateOpens(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = fixedRepository(Optional.of(adminInfo(true)));
        ApiKeyAuthHandler auth = disabled(repository, CidrList.EMPTY, false);
        echoServer(vertx, auth).compose(server -> {
            WebClient c = WebClient.create(vertx);
            int port = server.actualPort();
            return Future.all(List.of(
                            c.post(port, "localhost", "/R4/validator").send(),
                            c.post(port, "localhost", "/R4/validate").send(),
                            c.get(port, "localhost", "/igs/x/1.0.0/dependencies").send(),
                            c.get(port, "localhost", "/health").send()))
                    .compose(closed -> {
                        ctx.verify(() -> verifyNoInteractions(repository)); // nothing is resolved while closed
                        auth.openGate();
                        return c.post(port, "localhost", "/R4/validate").send().map(open -> List.of(closed, open));
                    });
        }).onComplete(ctx.succeeding(results -> ctx.verify(() -> {
            io.vertx.core.CompositeFuture closed = (io.vertx.core.CompositeFuture) results.get(0);
            List<HttpResponse<Buffer>> responses = closed.list();
            for (int i = 0; i < 3; i++) {
                assertEquals(503, responses.get(i).statusCode(), responses.get(i).bodyAsString());
                assertEquals("5", responses.get(i).getHeader("Retry-After"));
            }
            assertEquals(200, responses.get(3).statusCode(), "open paths aren't gated");
            assertEquals(200, ((HttpResponse<?>) results.get(1)).statusCode(), "open gate: served");
            ctx.completeNow();
        })));
    }

    /** T040: in enabled mode the gate changes nothing (4.1 behaviour). */
    @Test
    void enabledModeIgnoresTheGate(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findActive(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        ApiKeyAuthHandler auth = new ApiKeyAuthHandler(repository, 60_000, MAX_CACHED);
        echoServer(vertx, auth)
                .compose(server -> WebClient.create(vertx).post(server.actualPort(), "localhost", "/R4/validate")
                        .putHeader("Authorization", "Bearer unknown-key-0123456789abcdefghijklmnop").send())
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(401, r.statusCode(), "keys are checked; no 503 although the gate was never opened");
                    ctx.completeNow();
                })));
    }

    /** T047, FR-030: the admin network allowlist still applies, before any caller is set. */
    @Test
    void disabledModeKeepsTheAdminNetworkAllowlist(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository repository = fixedRepository(Optional.of(adminInfo(true)));
        ApiKeyAuthHandler auth = disabled(repository, CidrList.parse("10.0.0.0/8"), true);
        echoServer(vertx, auth).compose(server -> {
            WebClient c = WebClient.create(vertx);
            return Future.all(c.post(server.actualPort(), "localhost", "/R4/validator").send(),
                    c.post(server.actualPort(), "localhost", "/R4/validate").send());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            HttpResponse<Buffer> admin = cf.resultAt(0);
            HttpResponse<Buffer> validate = cf.resultAt(1);
            assertEquals(403, admin.statusCode(), admin.bodyAsString());
            assertTrue(admin.bodyAsString().contains("not allowed from this network"));
            assertEquals(200, validate.statusCode(), "validation isn't administrative");
            ctx.completeNow();
        })));
    }

    /** T047: a deactivated fixed caller organisation is 403; a revoked fixed key is 403 naming the restart. */
    @Test
    void disabledModeRefusesAnInactiveOrganisationOrARevokedFixedKey(Vertx vertx, VertxTestContext ctx) {
        ApiKeyRepository inactive = fixedRepository(Optional.of(adminInfo(false)));
        ApiKeyRepository revoked = fixedRepository(Optional.empty());
        Future.all(echoServer(vertx, disabled(inactive, CidrList.EMPTY, true)),
                        echoServer(vertx, disabled(revoked, CidrList.EMPTY, true)))
                .compose(servers -> {
                    WebClient c = WebClient.create(vertx);
                    return Future.all(
                            c.post(servers.<io.vertx.core.http.HttpServer>resultAt(0).actualPort(), "localhost", "/R4/validator").send(),
                            c.post(servers.<io.vertx.core.http.HttpServer>resultAt(1).actualPort(), "localhost", "/R4/validator").send());
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<Buffer> a = cf.resultAt(0);
                    HttpResponse<Buffer> b = cf.resultAt(1);
                    assertEquals(403, a.statusCode());
                    assertTrue(a.bodyAsString().contains("deactivated"), a.bodyAsString());
                    assertEquals(403, b.statusCode());
                    assertTrue(b.bodyAsString().contains("fixed caller key is revoked"), b.bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** Code review regression: a revoked fixed key is cached like a found one, so refusals cost no query each. */
    @Test
    void aRevokedFixedKeyIsLookedUpOncePerTtl(VertxTestContext ctx) {
        ApiKeyRepository repository = fixedRepository(Optional.empty());
        FixedCallers fixed = new FixedCallers(repository, 60_000);
        fixed.admin().transform(first -> fixed.admin()).transform(second -> fixed.admin())
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed());
                    org.mockito.Mockito.verify(repository, times(1)).findById(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID);
                    ctx.completeNow();
                }));
    }
}
