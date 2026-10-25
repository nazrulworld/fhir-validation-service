package nzi.fhir.validator.core.service;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class IgPackageServiceTest extends BaseTestContainer {

    private Vertx vertx;
    private IgPackageService igPackageService;
    private static final String US_CORE_PACKAGE_PATH = "/ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz";
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
        Pool pgPool = getPgPool(vertx);
        
        igPackageService.registerIg(packageContent, false)
            .compose(npmPackage -> {
                assertNotNull(npmPackage);
                assertEquals(PACKAGE_NAME, npmPackage.name());
                assertEquals(PACKAGE_VERSION, npmPackage.version());
                
                return igPackageService.loadIgPackage(PACKAGE_NAME, PACKAGE_VERSION);
            })
            .compose(loadedPackage -> {
                assertNotNull(loadedPackage);
                assertEquals(PACKAGE_NAME, loadedPackage.name());
                assertEquals(PACKAGE_VERSION, loadedPackage.version());
                
                return igPackageService.cacheManager().isPackageExists(PACKAGE_NAME, PACKAGE_VERSION);
            })
            .onComplete(testContext.succeeding(exists -> {
                assertTrue(exists);
                testContext.completeNow();
            }));
}

    @Test
    @org.junit.jupiter.api.condition.EnabledIf("hasInternetConnection")
    @DisplayName("Should successfully register US Core package via official source")
    void whenRegisterUsCoreThroughOfficialSource_thenSucceeds(VertxTestContext testContext) {
        nzi.fhir.validator.core.config.ApplicationConfig config = nzi.fhir.validator.core.config.ApplicationConfig.of(
                new io.vertx.core.json.JsonObject().put("profile", "dev"));
        io.vertx.core.WorkerExecutor workers = vertx.createSharedWorkerExecutor("registry-it", 2);
        nzi.fhir.validator.core.npm.PostgresPackageCacheManager registryManager = new nzi.fhir.validator.core.npm.PostgresPackageCacheManager(
                getPgPool(vertx), workers, nzi.fhir.validator.core.npm.PackageArchiveGuard.defaults(),
                nzi.fhir.validator.core.npm.SafePackageFetcher.create(vertx, workers, config), config.packageRegistryUrls(), true);
        IgPackageService.create(vertx, getPgPool(vertx), registryManager).registerIg(PACKAGE_NAME, PACKAGE_VERSION)
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
    @DisplayName("Lists the files of a stored package (deprecated conformance operation, B21)")
    void whenListPackageFiles_thenSucceeds() throws IOException {
        byte[] packageContent = loadUsCorePackage();

        igPackageService.registerIg(packageContent)
                .compose(v -> igPackageService.listPackageFiles(PACKAGE_NAME, PACKAGE_VERSION))
                .onComplete(ar -> {
                    assertTrue(ar.succeeded(), String.valueOf(ar.cause()));
                    JsonObject report = ar.result();
                    assertEquals(PACKAGE_NAME, report.getString("name"));
                    assertEquals(PACKAGE_VERSION, report.getString("version"));
                    assertFalse(report.containsKey("conformance"), "no fake conformance verdict");
                    assertTrue(report.getJsonArray("files").size() > 0);
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