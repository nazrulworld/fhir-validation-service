package nzi.fhir.validator.web.service;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import nzi.fhir.validator.web.testcontainers.BaseTestContainer;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletionException;

import static nzi.fhir.validator.web.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class IgPackageServiceTest extends BaseTestContainer {

    private Vertx vertx;
    private IgPackageService igPackageService;
    private static final String US_CORE_PACKAGE_PATH = "/hl7.fhir.us.core-7.0.0.tgz";
    private static final String PACKAGE_NAME = "hl7.fhir.us.core";
    private static final String PACKAGE_VERSION = "7.0.0";

    @BeforeEach
    void setUp() {
        startContainers();
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);
        igPackageService = IgPackageService.create(vertx, pgPool);

        // Create the necessary table
        createTables(pgPool);
    }

    private byte[] loadUsCorePackage() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(US_CORE_PACKAGE_PATH)) {
            if (is == null) {
                throw new IOException("Could not find US Core package in resources: " + US_CORE_PACKAGE_PATH);
            }
            return is.readAllBytes();
        }
    }

    @Test
    @DisplayName("Should successfully register and load US Core IG package")
    void whenRegisterAndLoadUsCorePackage_thenSucceeds(VertxTestContext testContext) throws IOException {
        byte[] packageContent = loadUsCorePackage();

        igPackageService.registerIg(packageContent)
                .compose(npmPackage -> {
                    assertNotNull(npmPackage);
                    assertEquals(PACKAGE_NAME, npmPackage.name());
                    assertEquals(PACKAGE_VERSION, npmPackage.version());
                    
                    return igPackageService.loadIgPackage(PACKAGE_NAME, PACKAGE_VERSION);
                })
                .onComplete(testContext.succeeding(loadedPackage -> {
                    assertNotNull(loadedPackage);
                    assertEquals(PACKAGE_NAME, loadedPackage.name());
                    assertEquals(PACKAGE_VERSION, loadedPackage.version());
                    testContext.completeNow();
                }));
}

    @Test
    @DisplayName("Should successfully register US Core package via official source")
    void whenRegisterUsCoreThroughOfficialSource_thenSucceeds(VertxTestContext testContext) {
        igPackageService.registerIg(PACKAGE_NAME, PACKAGE_VERSION)
                .onComplete(testContext.succeeding(npmPackage -> {
                    assertNotNull(npmPackage);
                    assertEquals(PACKAGE_NAME, npmPackage.name());
                    assertEquals(PACKAGE_VERSION, npmPackage.version());
                    testContext.completeNow();
                }));
}

    @Test
    @DisplayName("Should successfully generate dependency graph for US Core")
    void whenGenerateDependencyGraph_thenSucceeds() throws IOException {
        byte[] packageContent = loadUsCorePackage();

        igPackageService.registerIg(packageContent)
                .compose(v -> igPackageService.getDependencyGraph(PACKAGE_NAME, PACKAGE_VERSION))
                .onComplete(ar -> {
                    assertTrue(ar.succeeded());
                    JsonObject graph = ar.result();
                    assertNotNull(graph);
                    assertEquals(PACKAGE_NAME, graph.getString("name"));
                    assertEquals(PACKAGE_VERSION, graph.getString("version"));
                    assertNotNull(graph.getJsonArray("dependencies"));
                    assertTrue(graph.getJsonArray("dependencies").size() > 0);
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @Test
    @DisplayName("Should successfully generate conformance report for US Core")
    void whenGenerateConformanceReport_thenSucceeds() throws IOException {
        byte[] packageContent = loadUsCorePackage();

        igPackageService.registerIg(packageContent)
                .compose(v -> igPackageService.generateConformanceReport(PACKAGE_NAME, PACKAGE_VERSION))
                .onComplete(ar -> {
                    assertTrue(ar.succeeded());
                    JsonObject report = ar.result();
                    assertNotNull(report);
                    assertEquals(PACKAGE_NAME, report.getString("name"));
                    assertEquals(PACKAGE_VERSION, report.getString("version"));
                    assertEquals("basic", report.getString("conformance"));
                    assertNotNull(report.getJsonArray("resources"));
                    assertTrue(report.getJsonArray("resources").size() > 0);
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @Test
    @DisplayName("Should fail when loading non-existent IG package")
    void whenLoadNonExistentPackage_thenFails(VertxTestContext testContext) {
        igPackageService.loadIgPackage("non-existent", "1.0.0")
        .onComplete(npmPackage -> {
            assertTrue(npmPackage.succeeded());
            assertNull(npmPackage.result());
            testContext.completeNow();
        });
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            if(hasPgPool()){
                Pool pool = getPgPool(vertx);
                dropTables(pool);
                pool.close();
                removePgPool();
            }
            vertx.close();
        }
    }
}