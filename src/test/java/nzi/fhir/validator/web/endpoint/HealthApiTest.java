package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test cases for the HealthApi endpoints
 * 
 * @author Md Nazrul Islam
 */
@ExtendWith(VertxExtension.class)
class HealthApiTest extends BaseTestContainer {
    // Use a random port to avoid conflicts
    private int testPort;
    private Vertx vertx;
    private WebClient client;
    private HealthService healthService;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Start containers if not already started
        startContainers();

        // Initialize Vertx
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        client = WebClient.create(vertx);

        RouterBuilder.create(vertx, "openapi.yaml")
                .onSuccess(routerBuilder -> {
                    // Like HttpVerticle: keys are enforced at router level, not by OpenAPI security handlers (spec 008).
                    routerBuilder.setOptions(new io.vertx.ext.web.openapi.RouterBuilderOptions().setRequireSecurityHandlers(false));
                    healthService = new HealthService(vertx, pgPool);
                    new HealthApi(routerBuilder, vertx, healthService);
                    // Create the router from the builder
                    Router router = routerBuilder.createRouter();
                    // Use port 0 to get a random available port
                    vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(0)
                            .onSuccess(server -> {
                                testPort = server.actualPort();
                                System.out.println("Test server started on port " + testPort);
                                testContext.completeNow();
                            })
                            .onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        // Close resources
        if(hasPgPool()){
            Pool pool = getPgPool(vertx);
            pool.close();
            removePgPool();
        }

        if (vertx != null) {
            vertx.close()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testHealthEndpoint(VertxTestContext testContext) {
        client.get(testPort, "localhost", "/health")
                .as(BodyCodec.jsonObject())
                .send()
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();

                    // Check main health status
                    assertNotNull(responseBody.getString("status"));
                    // The overall status might be UP or DOWN depending on the services
                    assertNotNull(responseBody.getLong("timestamp"));

                    // Check postgres status
                    JsonObject postgres = responseBody.getJsonObject("postgres");
                    assertNotNull(postgres);
                    assertNotNull(postgres.getString("status"));
                    // If status is UP, check response time
                    if ("UP".equals(postgres.getString("status"))) {
                        assertNotNull(postgres.getLong("responseTime"));
                    }
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /** Spec 007 (contracts §5): the terminology allowlist state is reported; readiness doesn't depend on it. */
    @Test
    void healthReportsTheTerminologyAllowlist(VertxTestContext testContext) {
        healthService.setTerminologyStatus(() -> new JsonObject()
                .put("registry", nzi.fhir.validator.core.terminology.TxRegistry.OFFICIAL_BASE)
                .put("allowlist", nzi.fhir.validator.core.terminology.TxServerAllowlist.builtIn().status()));
        String readinessBefore = healthService.readiness().getString("status");
        client.get(testPort, "localhost", "/health")
                .as(BodyCodec.jsonObject())
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    JsonObject body = response.body();
                    nzi.fhir.validator.testsupport.ContractAssertions.assertDeclaredBySchema(body, "HealthResponse", "terminology");
                    JsonObject terminology = body.getJsonObject("terminology");
                    assertEquals("https://ecosystem.hl7.org/tx-reg", terminology.getString("registry"));
                    JsonObject r4 = terminology.getJsonObject("allowlist").getJsonObject("R4");
                    assertEquals("built-in", r4.getString("source"));
                    assertEquals(1, r4.getInteger("servers"));
                    assertTrue(r4.containsKey("fetchedAt") && r4.getValue("fetchedAt") == null);
                    assertTrue(r4.getString("reason").contains("built-in fallback list in use"));
                    assertEquals(0, terminology.getJsonObject("allowlist").getJsonObject("R4B").getInteger("servers"));
                    assertEquals(readinessBefore, healthService.readiness().getString("status"),
                            "readiness doesn't depend on the allowlist");
                    testContext.completeNow();
                })));
    }

    @Test
    void testLivenessEndpoint(VertxTestContext testContext) {
        client.get(testPort, "localhost", "/health/liveness")
                .as(BodyCodec.jsonObject())
                .send()
                .onSuccess(response -> {
                    // Verify response
                    assertEquals(200, response.statusCode());
                    JsonObject responseBody = response.body();

                    // Check liveness status
                    assertEquals("UP", responseBody.getString("status"));
                    assertNotNull(responseBody.getLong("timestamp"));

                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Constitution IV regression: an unreachable database is reported without the driver's error text.
     */
    @Test
    void readinessDoesNotLeakDatabaseErrors(VertxTestContext testContext) {
        Pool unreachable = PgBuilder.pool()
                .connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(1).setDatabase("x").setUser("x").setPassword("x"))
                .using(vertx)
                .build();
        HealthService healthService = new HealthService(vertx, unreachable, 1_000).start();
        vertx.setTimer(1_500, id -> RouterBuilder.create(vertx, "openapi.yaml")
                .compose(routerBuilder -> {
                    routerBuilder.setOptions(new io.vertx.ext.web.openapi.RouterBuilderOptions().setRequireSecurityHandlers(false));
                    new HealthApi(routerBuilder, vertx, healthService);
                    return vertx.createHttpServer().requestHandler(routerBuilder.createRouter()).listen(0);
                })
                .compose(server -> client.get(server.actualPort(), "localhost", "/health/readiness").send())
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    healthService.stop();
                    assertEquals(503, response.statusCode());
                    String body = response.bodyAsString();
                    assertFalse(body.contains("127.0.0.1") || body.contains("refused") || body.contains("Exception")
                            || body.contains("java."), body);
                    assertEquals("database not reachable",
                            response.bodyAsJsonObject().getJsonArray("reasons").getString(0));
                    testContext.completeNow();
                }))));
    }

    /**
     * Spec 005 FR-021, research R12: the unauthenticated health report lists only fully shareable contexts; private
     * ones are counted by state, and no owner or private key appears anywhere in it.
     */
    @Test
    void healthListsOnlyShareableContextsAndNeverOwners(VertxTestContext testContext) {
        resetSchema();
        java.util.concurrent.atomic.AtomicReference<nzi.fhir.validator.testsupport.CoreHarness> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        nzi.fhir.validator.testsupport.CoreHarness.start(vertx, connectOptions())
                .compose(core -> {
                    holder.set(core);
                    var services = core.services();
                    return nzi.fhir.validator.testsupport.TestPackages.createOrganisation(services.pool(), "secretorg")
                            .compose(org -> nzi.fhir.validator.testsupport.TestPackages.storePackage(services,
                                            nzi.fhir.validator.testsupport.TestPackages.profilePackage("health.public", "1.0.0"))
                                    .compose(v -> nzi.fhir.validator.testsupport.TestPackages.storePackage(services,
                                            nzi.fhir.validator.testsupport.TestPackages.profilePackage("health.secret", "1.0.0")))
                                    .compose(v -> nzi.fhir.validator.testsupport.TestPackages.setPackageAccess(services.pool(),
                                            "health.secret#1.0.0", org, false))
                                    .compose(v -> services.pool().withTransaction(tx ->
                                            nzi.fhir.validator.core.service.ConfigRevisionRepository.lockForUpdate(tx,
                                                            nzi.fhir.validator.core.enums.SupportedFhirVersion.R4)
                                                    .compose(row -> nzi.fhir.validator.core.service.ConfigRevisionRepository.updateIgSet(tx,
                                                            nzi.fhir.validator.core.enums.SupportedFhirVersion.R4,
                                                            java.util.List.of("health.public#1.0.0"), "health.public#1.0.0",
                                                            nzi.fhir.validator.core.service.ConfigRevisionRepository.KIND_IG))
                                                    .compose(rev -> nzi.fhir.validator.core.service.ConfigRevisionRepository.updateIgSet(tx,
                                                            nzi.fhir.validator.core.enums.SupportedFhirVersion.R4,
                                                            java.util.List.of("health.public#1.0.0", "health.secret#1.0.0"),
                                                            "health.secret#1.0.0", nzi.fhir.validator.core.service.ConfigRevisionRepository.KIND_IG,
                                                            org, false)))));
                })
                .compose(rev -> holder.get().services().configSync().syncCatalogNow())
                .compose(v -> holder.get().services().configSync().syncNow(nzi.fhir.validator.core.enums.SupportedFhirVersion.R4))
                .onComplete(ar -> {
                    nzi.fhir.validator.testsupport.CoreHarness core = holder.get();
                    try {
                        testContext.verify(() -> {
                            if (ar.failed()) {
                                throw new AssertionError(ar.cause());
                            }
                            JsonObject report = core.services().healthService().readiness();
                            JsonObject r4 = report.getJsonObject("versions").getJsonObject("R4");
                            io.vertx.core.json.JsonArray contexts = r4.getJsonArray("contexts");
                            assertEquals(1, contexts.size(), r4.encode());
                            assertEquals("health.public#1.0.0", contexts.getJsonObject(0).getString("key"));
                            JsonObject priv = r4.getJsonObject("privateContexts");
                            assertEquals(1, priv.getInteger("count"));
                            assertEquals(1, priv.getJsonObject("states").getInteger("ACTIVE"), priv.encode());
                            String text = report.encode();
                            assertFalse(text.contains("health.secret"), "the private key never appears: " + text);
                            assertFalse(text.contains("secretorg"), "owners never appear: " + text);
                            assertFalse(text.contains("owner"), text);
                        });
                    } finally {
                        core.stop().onComplete(x -> testContext.completeNow());
                    }
                });
    }
}
