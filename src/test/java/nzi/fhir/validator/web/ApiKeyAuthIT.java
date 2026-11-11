package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static nzi.fhir.validator.testsupport.ContractAssertions.assertProblem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 regression / SC-006: who may call what (FR-012).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class ApiKeyAuthIT extends BaseTestContainer {
    private static final String VALIDATE_KEY = "validate-key-0123456789abcdefghijklmnopq";
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    private AppHarness app;

    private Future<AppHarness> start(Vertx vertx, JsonObject authOverrides) {
        resetSchema();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.getJsonObject("auth").mergeIn(authOverrides);
        if (authOverrides.containsKey("bootstrapAdminKey") && authOverrides.getValue("bootstrapAdminKey") == null) {
            config.getJsonObject("auth").remove("bootstrapAdminKey");
        }
        return AppHarness.start(vertx, config).compose(a -> {
            app = a;
            return a.awaitReady(90_000).map(a);
        });
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private HttpRequest<Buffer> req(String method, String path, String key) {
        HttpRequest<Buffer> r = switch (method) {
            case "GET" -> app.client().get(app.port(), "localhost", path);
            case "DELETE" -> app.client().delete(app.port(), "localhost", path);
            case "PATCH" -> app.client().patch(app.port(), "localhost", path);
            default -> app.client().post(app.port(), "localhost", path);
        };
        r.timeout(5_000);
        if (key != null) {
            r.putHeader("Authorization", "Bearer " + key);
        }
        return r;
    }

    private Future<List<HttpResponse<Buffer>>> callAdminOperations(String key) {
        return Future.all(
                req("POST", "/igs/register", key).sendJsonObject(new JsonObject().put("name", "x")),
                req("POST", "/R4/validator", key).sendJsonObject(new JsonObject().put("igPackageId", "x")),
                req("POST", "/igs/upload", key).sendBuffer(Buffer.buffer("x")),
                // Spec 004: excluding an IG is administrative too (every method on the path, not only POST).
                req("DELETE", "/R4/validator?ig=x%231.0.0", key).send()
        ).map(cf -> cf.list());
    }

    private Future<HttpResponse<Buffer>> validate(String key) {
        return req("POST", "/R4/validate", key).putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT));
    }

    @Test
    void withoutAnyAdminKeyConfiguredAdminOperationsAreRefused(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().putNull("bootstrapAdminKey"))
                .compose(a -> callAdminOperations(AppHarness.ADMIN_KEY))
                .onComplete(ctx.succeeding(responses -> ctx.verify(() -> {
                    responses.forEach(r -> assertProblem(r, 401, "unauthorized"));
                    ctx.completeNow();
                })));
    }

    /** Spec 008 US1 (FR-008): administrative operations need ADMIN; validation needs VALIDATE or ADMIN. */
    @Test
    void adminOperationsNeedAnAdminKeyAndValidationNeedsAKey(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject())
                .compose(a -> callAdminOperations(null))
                .compose(noKey -> {
                    ctx.verify(() -> noKey.forEach(r -> {
                        assertProblem(r, 401, "unauthorized");
                        assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
                    }));
                    return callAdminOperations("wrong-key-0123456789abcdefghijklmnopqrstu");
                })
                .compose(wrongKey -> {
                    ctx.verify(() -> wrongKey.forEach(r -> assertProblem(r, 401, "unauthorized")));
                    return app.bootstrap().services().igPackageService().registerIg(
                                    TestPackages.profilePackage("example.auth", "1.0.0",
                                            TestPackages.requiredPatientElements("http://example.org/fhir/StructureDefinition/ok", "gender")), false)
                            .compose(pkg -> req("POST", "/R4/validator", AppHarness.ADMIN_KEY)
                                    .sendJsonObject(new JsonObject().put("igPackageId", "example.auth").put("igPackageVersion", "1.0.0")));
                })
                .compose(adminOk -> {
                    ctx.verify(() -> assertEquals(200, adminOk.statusCode(), adminOk.bodyAsString()));
                    return app.bootstrap().services().apiKeys().create("validator", VALIDATE_KEY, ApiKeyRepository.Role.VALIDATE, nzi.fhir.validator.core.org.Organisations.SYSTEM_ID);
                })
                .compose(v -> req("POST", "/R4/validator", VALIDATE_KEY)
                        .sendJsonObject(new JsonObject().put("igPackageId", "example.auth").put("igPackageVersion", "1.0.0")))
                .compose(forbidden -> {
                    ctx.verify(() -> assertProblem(forbidden, 403, "forbidden"));
                    return req("DELETE", "/R4/validator?ig=example.auth%231.0.0", VALIDATE_KEY).send();
                })
                .compose(forbiddenExclude -> {
                    ctx.verify(() -> assertProblem(forbiddenExclude, 403, "forbidden"));
                    return req("DELETE", "/R4/validator?ig=example.auth%231.0.0", AppHarness.ADMIN_KEY).send();
                })
                .compose(adminExclude -> {
                    ctx.verify(() -> assertEquals(200, adminExclude.statusCode(), adminExclude.bodyAsString()));
                    return Future.all(validate(null), req("GET", "/igs/none/1.0.0/dependencies", null).send(),
                            req("GET", "/igs/none/1.0.0/conformance", null).send());
                })
                .compose(anonymous -> {
                    ctx.verify(() -> anonymous.<HttpResponse<Buffer>>list().forEach(r -> {
                        assertProblem(r, 401, "unauthorized");
                        assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
                    }));
                    return Future.all(validate(VALIDATE_KEY), validate(AppHarness.ADMIN_KEY), validate(AppHarness.VALIDATOR_KEY));
                })
                .compose(withKeys -> {
                    ctx.verify(() -> withKeys.<HttpResponse<Buffer>>list().forEach(r -> assertEquals(200, r.statusCode(), r.bodyAsString())));
                    return Future.all(req("GET", "/health", null).send(), req("GET", "/health/readiness", null).send(),
                            req("GET", "/health/liveness", null).send());
                })
                .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                    health.list().forEach(r -> assertEquals(200, ((HttpResponse<?>) r).statusCode()));
                    ctx.completeNow();
                })));
    }

    @Test
    void validationRequiresAKeyAndHonoursRevocation(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject())
                .compose(a -> validate(null))
                .compose(anonymous -> {
                    ctx.verify(() -> assertProblem(anonymous, 401, "unauthorized"));
                    return app.bootstrap().services().apiKeys().create("validator", VALIDATE_KEY, ApiKeyRepository.Role.VALIDATE, nzi.fhir.validator.core.org.Organisations.SYSTEM_ID);
                })
                .compose(v -> validate(VALIDATE_KEY))
                .compose(withKey -> {
                    ctx.verify(() -> assertEquals(200, withKey.statusCode(), withKey.bodyAsString()));
                    return req("GET", "/igs/none/1.0.0/dependencies", null).send();
                })
                .compose(readAnonymous -> {
                    ctx.verify(() -> assertProblem(readAnonymous, 401, "unauthorized"));
                    return app.bootstrap().services().apiKeys().revoke("validator");
                })
                .compose(v -> Await.until(vertx, 5_000, "revoked key is refused",
                        () -> validate(VALIDATE_KEY).map(r -> r.statusCode() == 401)))
                .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Spec 005 FR-014, SC-005: a key of a deactivated organisation gets 403 on validation and administrative paths
     * within the auth-cache TTL, and works again after reactivation.
     */
    @Test
    void keysOfADeactivatedOrganisationAreForbiddenUntilReactivated(Vertx vertx, VertxTestContext ctx) {
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> org = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> validateKey = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> adminKey = new java.util.concurrent.atomic.AtomicReference<>();
        start(vertx, new JsonObject().put("cacheTtlMs", 200))
                .compose(a -> TestPackages.createOrganisation(a.bootstrap().services().pool(), "acme"))
                .compose(id -> {
                    org.set(id);
                    return TestPackages.createKey(app.bootstrap().services().pool(), id, "acme-val", "VALIDATE");
                })
                .compose(k -> {
                    validateKey.set(k);
                    return TestPackages.createKey(app.bootstrap().services().pool(), org.get(), "acme-admin", "ADMIN");
                })
                .compose(k -> {
                    adminKey.set(k);
                    return validate(validateKey.get());
                })
                .compose(active -> {
                    ctx.verify(() -> assertEquals(200, active.statusCode(), active.bodyAsString()));
                    return app.bootstrap().services().organisations().setActive(org.get(), false);
                })
                .compose(v -> Await.until(vertx, 5_000, "a deactivated organisation's key is refused",
                        () -> validate(validateKey.get()).map(r -> r.statusCode() == 403)))
                .compose(v -> Future.all(validate(validateKey.get()),
                        req("POST", "/R4/validator", adminKey.get()).sendJsonObject(new JsonObject().put("igPackageId", "x")),
                        // The package read endpoints are authorised paths too (openapi.yaml declares the 403).
                        req("GET", "/igs/none/1.0.0/dependencies", validateKey.get()).send()))
                .compose(refused -> {
                    ctx.verify(() -> refused.<HttpResponse<Buffer>>list().forEach(r -> {
                        assertProblem(r, 403, "forbidden");
                        assertTrue(r.bodyAsJsonObject().getString("detail").contains("deactivated"), r.bodyAsString());
                    }));
                    return app.bootstrap().services().organisations().setActive(org.get(), true);
                })
                .compose(v -> Await.until(vertx, 5_000, "a reactivated organisation's key works again",
                        () -> validate(validateKey.get()).map(r -> r.statusCode() == 200)))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** Spec 005 contracts §5: PATCH /igs/{name}/{version} is administrative (every method on the path needs ADMIN). */
    @Test
    void packageAccessChangeNeedsAnAdminKey(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject())
                .compose(a -> app.bootstrap().services().apiKeys().create("validator", VALIDATE_KEY, ApiKeyRepository.Role.VALIDATE, nzi.fhir.validator.core.org.Organisations.SYSTEM_ID))
                .compose(v -> Future.all(
                        req("PATCH", "/igs/a/1.0.0", null).sendJsonObject(new JsonObject().put("shareable", false)),
                        req("PATCH", "/igs/a/1.0.0", VALIDATE_KEY).sendJsonObject(new JsonObject().put("shareable", false)),
                        req("PATCH", "/R4/validator?ig=a%231.0.0", null).sendJsonObject(new JsonObject().put("shareable", false)),
                        req("PATCH", "/R4/validator?ig=a%231.0.0", VALIDATE_KEY).sendJsonObject(new JsonObject().put("shareable", false))))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    List<HttpResponse<Buffer>> r = cf.list();
                    assertProblem(r.get(0), 401, "unauthorized");
                    assertProblem(r.get(1), 403, "forbidden");
                    assertProblem(r.get(2), 401, "unauthorized");
                    assertProblem(r.get(3), 403, "forbidden");
                    ctx.completeNow();
                })));
    }

    /**
     * 4.0.0: the validator context endpoints moved from /{version}/include-ig to /{version}/validator. The new path is
     * administrative for every method; /{version}/validate (one letter shorter) stays a validation path (a VALIDATE key
     * is enough); the old path no longer exists.
     */
    // --- Spec 008 US4: admin network allowlist and trusted proxies (FR-002 to FR-004) -------------------------------

    /** An administrative request from 127.0.0.1 (IPv4 explicitly, whatever "localhost" resolves to). */
    private Future<HttpResponse<Buffer>> adminFromLoopback(String key, String xff) {
        HttpRequest<Buffer> r = app.client().post(app.port(), "127.0.0.1", "/R4/validator").timeout(5_000);
        if (key != null) {
            r.putHeader("Authorization", "Bearer " + key);
        }
        if (xff != null) {
            r.putHeader("X-Forwarded-For", xff);
        }
        return r.sendJsonObject(new JsonObject().put("igPackageId", "x"));
    }

    private static void assertNetworkRefusal(HttpResponse<Buffer> r) {
        assertProblem(r, 403, "forbidden");
        assertTrue(r.bodyAsJsonObject().getString("detail").contains("not allowed from this network"), r.bodyAsString());
    }

    private static void assertPastTheAllowlist(HttpResponse<Buffer> r) {
        assertNotEquals(401, r.statusCode(), r.bodyAsString());
        assertNotEquals(403, r.statusCode(), r.bodyAsString());
    }

    @Test
    void adminOutsideTheAllowlistIsForbiddenForEveryKeyAndValidationIsUnaffected(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().put("adminCidrs", "10.0.0.0/8"))
                .compose(a -> Future.all(adminFromLoopback(AppHarness.ADMIN_KEY, null),
                        adminFromLoopback("wrong-key-0123456789abcdefghijklmnopqrstu", null), adminFromLoopback(null, null),
                        // Spoofing: without trusted proxies the header is ignored.
                        adminFromLoopback(AppHarness.ADMIN_KEY, "10.1.2.3")))
                .compose(refused -> {
                    ctx.verify(() -> refused.<HttpResponse<Buffer>>list().forEach(ApiKeyAuthIT::assertNetworkRefusal));
                    return app.client().post(app.port(), "127.0.0.1", "/R4/validate").putHeader("Authorization", AppHarness.VALIDATOR_AUTH)
                            .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT));
                })
                .onComplete(ctx.succeeding(validation -> ctx.verify(() -> {
                    assertEquals(200, validation.statusCode(), validation.bodyAsString());
                    ctx.completeNow();
                })));
    }

    @Test
    void adminInsideTheAllowlistIsServedAndNoAllowlistMeansNoRestriction(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().put("adminCidrs", "127.0.0.0/8, ::1"))
                .compose(a -> Future.all(adminFromLoopback(AppHarness.ADMIN_KEY, null), adminFromLoopback(null, null)))
                .compose(cf -> {
                    ctx.verify(() -> {
                        assertPastTheAllowlist(cf.resultAt(0));
                        assertProblem(cf.resultAt(1), 401, "unauthorized");
                    });
                    return app.stop().compose(v -> start(vertx, new JsonObject()));
                })
                .compose(a -> adminFromLoopback(AppHarness.ADMIN_KEY, null))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertPastTheAllowlist(r);
                    ctx.completeNow();
                })));
    }

    @Test
    void forwardedAddressesCountOnlyThroughTrustedProxies(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject().put("adminCidrs", "10.0.0.0/8").put("trustedProxies", "127.0.0.1/32, ::1"))
                .compose(a -> Future.all(adminFromLoopback(AppHarness.ADMIN_KEY, "10.1.2.3"),
                        adminFromLoopback(AppHarness.ADMIN_KEY, "10.1.2.3, 198.51.100.9"),
                        adminFromLoopback(AppHarness.ADMIN_KEY, null),
                        // Review regression: a client-supplied Forwarded header is never read when the proxies write
                        // X-Forwarded-For (the default), so it can't pick an allowlisted address.
                        app.client().post(app.port(), "127.0.0.1", "/R4/validator").timeout(5_000)
                                .putHeader("Authorization", AppHarness.ADMIN_AUTH).putHeader("Forwarded", "for=10.0.0.5")
                                .sendJsonObject(new JsonObject().put("igPackageId", "x"))))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertPastTheAllowlist(cf.resultAt(0));
                    assertNetworkRefusal(cf.resultAt(1));
                    assertNetworkRefusal(cf.resultAt(2));
                    assertNetworkRefusal(cf.resultAt(3));
                    ctx.completeNow();
                })));
    }

    // --- Spec 008 US5: key expiry and the last-used address (FR-006, FR-007) ----------------------------------------

    private Future<java.util.List<String>> keyColumn(String sql) {
        return app.bootstrap().services().pool().query(sql).execute().map(rows -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            rows.forEach(r -> out.add(r.getString(0)));
            return out;
        });
    }

    @Test
    void expiredKeyIsRefusedEvenWhenCachedAndKeysWithoutExpiryKeepWorking(Vertx vertx, VertxTestContext ctx) {
        String expiring = "expiring-key-0123456789abcdefghijklmnopq";
        start(vertx, new JsonObject().put("cacheTtlMs", 60_000))
                .compose(a -> app.bootstrap().services().apiKeys().create("expiring", expiring, ApiKeyRepository.Role.VALIDATE,
                        nzi.fhir.validator.core.org.Organisations.SYSTEM_ID, java.time.Instant.now().plusSeconds(2)))
                .compose(v -> validate(expiring))
                .compose(before -> {
                    ctx.verify(() -> assertEquals(200, before.statusCode(), before.bodyAsString()));
                    return Await.until(vertx, 10_000, "the expired key is refused despite the 60 s cache",
                            () -> validate(expiring).map(r -> r.statusCode() == 401));
                })
                .compose(v -> validate(AppHarness.VALIDATOR_KEY))
                .onComplete(ctx.succeeding(neverExpires -> ctx.verify(() -> {
                    assertEquals(200, neverExpires.statusCode(), "the seeded key expires in 2099");
                    ctx.completeNow();
                })));
    }

    @Test
    void lastUsedIpIsRecordedAndANewAddressIsWrittenAtOnce(Vertx vertx, VertxTestContext ctx) {
        String sql = "SELECT host(last_used_ip) FROM fhir_validator_schema.api_keys WHERE id = '"
                + nzi.fhir.validator.core.Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'";
        start(vertx, new JsonObject().put("trustedProxies", "127.0.0.1/32, ::1"))
                .compose(a -> app.client().post(app.port(), "127.0.0.1", "/R4/validate").putHeader("Authorization", AppHarness.VALIDATOR_AUTH)
                        .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT)))
                .compose(r -> Await.until(vertx, 5_000, "last_used_ip = 127.0.0.1",
                        () -> keyColumn(sql).map(v -> v.equals(List.of("127.0.0.1")))))
                // Same key, a new client behind the trusted proxy: written within about a second, without waiting for
                // the 60 s throttle (address changes are written at most once a second per key).
                .compose(v -> Await.until(vertx, 5_000, "last_used_ip = 203.0.113.9",
                        () -> app.client().post(app.port(), "127.0.0.1", "/R4/validate").putHeader("Authorization", AppHarness.VALIDATOR_AUTH)
                                .putHeader("X-Forwarded-For", "203.0.113.9")
                                .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer(PATIENT))
                                .compose(r -> keyColumn(sql)).map(v2 -> v2.equals(List.of("203.0.113.9")))))
                .onComplete(ctx.succeedingThenComplete());
    }

    /** Spec 008 FR-010: the contract declares the key requirement and the 401 on every keyed operation. */
    @Test
    @SuppressWarnings("unchecked")
    void openApiDeclaresKeyedOperations() throws Exception {
        java.util.Map<String, Object> root;
        try (java.io.InputStream in = getClass().getResourceAsStream("/openapi.yaml")) {
            root = new org.yaml.snakeyaml.Yaml().load(java.util.Objects.requireNonNull(in));
        }
        java.util.Set<String> keyed = java.util.Set.of("validationApiValidate", "igPackageApiGetDependenciesGraph",
                "igPackageApiGenerateConformanceReport", "validatorApiIncludeIg", "validatorApiExcludeIg",
                "validatorApiChangeIncludedIg", "igPackageApiUploadIg", "igPackageApiRegisterIg", "igPackageApiChangeAccess");
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Map<String, Object> paths = (java.util.Map<String, Object>) root.get("paths");
        for (Object path : paths.values()) {
            for (Object op : ((java.util.Map<String, Object>) path).values()) {
                if (!(op instanceof java.util.Map<?, ?> m) || !m.containsKey("operationId")) {
                    continue;
                }
                String id = (String) m.get("operationId");
                Object security = m.get("security");
                if (keyed.contains(id)) {
                    seen.add(id);
                    assertNotNull(security, id + " declares security");
                    assertTrue(String.valueOf(security).contains("apiKey"), id + ": " + security);
                    assertTrue(((java.util.Map<Object, Object>) m.get("responses")).containsKey("401"), id + " lists 401");
                } else {
                    assertNull(security, id + " is open");
                }
            }
        }
        assertEquals(keyed, seen);
    }

    @Test
    void theValidatorPathIsAdministrativeAndTheOldPathIsGone(Vertx vertx, VertxTestContext ctx) {
        start(vertx, new JsonObject())
                .compose(a -> Future.all(
                        req("POST", "/R4/validator", null).sendJsonObject(new JsonObject().put("igPackageId", "x")),
                        req("PATCH", "/R4/validator?ig=x%231.0.0", null).sendJsonObject(new JsonObject().put("shareable", false)),
                        req("DELETE", "/R4/validator?ig=x%231.0.0", null).send(),
                        req("POST", "/R4/validate", AppHarness.VALIDATOR_KEY).putHeader("Content-Type", "application/fhir+json")
                                .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\",\"active\":true}")),
                        req("POST", "/R4/include-ig", AppHarness.ADMIN_KEY).sendJsonObject(new JsonObject().put("igPackageId", "x"))))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    for (int i = 0; i < 3; i++) {
                        HttpResponse<Buffer> r = cf.resultAt(i);
                        assertEquals(401, r.statusCode(), "anonymous call to /validator: " + r.bodyAsString());
                    }
                    HttpResponse<Buffer> validate = cf.resultAt(3);
                    assertEquals(200, validate.statusCode(), "/validate is a validation path: " + validate.bodyAsString());
                    HttpResponse<Buffer> old = cf.resultAt(4);
                    assertEquals(404, old.statusCode(), "/include-ig no longer exists: " + old.bodyAsString());
                    ctx.completeNow();
                })));
    }
}
