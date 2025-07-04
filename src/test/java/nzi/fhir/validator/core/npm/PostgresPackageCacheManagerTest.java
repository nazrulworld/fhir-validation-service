package nzi.fhir.validator.core.npm;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.model.IgPackageName;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PostgresPackageCacheManagerTest extends BaseTestContainer {

    private Vertx vertx;
    private PostgresPackageCacheManager cacheManager;
    private static final String TEST_PACKAGE_PATH = "/ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz";
    private static final String PACKAGE_ID = "hl7.fhir.us.core";
    private static final String PACKAGE_VERSION = "7.0.0";

    @BeforeEach
    void setUp() {
        startContainers();
        
        vertx = Vertx.vertx();
        Pool pgPool = getPgPool(vertx);
        initDatabaseScheme(pgPool);
        cacheManager = new PostgresPackageCacheManager(vertx, pgPool);
        
        createTables(pgPool);
    }

    private byte[] loadTestPackage() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(TEST_PACKAGE_PATH)) {
            if (is == null) {
                throw new IOException("Could not find test package in resources: " + TEST_PACKAGE_PATH);
            }
            return is.readAllBytes();
        }
    }

    @Test
    @DisplayName("Should handle null input stream")
    void whenNullInputStream_thenThrowException(VertxTestContext testContext) {
        cacheManager.addPackageToCache((InputStream)null)
            .onComplete(testContext.failing(error -> {
                assertTrue(error instanceof IllegalArgumentException);
                assertEquals("Input stream cannot be null", error.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should store and load package correctly")
    void whenStoreAndLoadPackage_thenSucceeds(VertxTestContext testContext) throws IOException {
        byte[] packageContent = loadTestPackage();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(packageContent);

        cacheManager.addPackageToCache(inputStream)
            .compose(npmPackage -> {
                assertNotNull(npmPackage);
                assertEquals(PACKAGE_ID, npmPackage.name());
                assertEquals(PACKAGE_VERSION, npmPackage.version());
                return cacheManager.loadPackage(PACKAGE_ID + "#" + PACKAGE_VERSION);
            })
            .onComplete(testContext.succeeding(loadedPackage -> {
                assertNotNull(loadedPackage);
                assertEquals(PACKAGE_ID, loadedPackage.name());
                assertEquals(PACKAGE_VERSION, loadedPackage.version());
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should handle package existence check correctly")
    void whenCheckPackageExists_thenReturnsCorrectResult(VertxTestContext testContext) throws IOException {
        byte[] packageContent = loadTestPackage();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(packageContent);
        IgPackageName packageName = new IgPackageName(PACKAGE_ID, PACKAGE_VERSION);

        cacheManager.addPackageToCache(inputStream)
            .compose(npmPackage -> cacheManager.isPackageExists(packageName))
            .onComplete(testContext.succeeding(exists -> {
                assertTrue(exists);
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should handle package removal correctly")
    void whenRemovePackage_thenSucceeds(VertxTestContext testContext) throws IOException {
        byte[] packageContent = loadTestPackage();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(packageContent);
        IgPackageName packageName = new IgPackageName(PACKAGE_ID, PACKAGE_VERSION);

        cacheManager.addPackageToCache(inputStream)
            .compose(npmPackage -> cacheManager.removePackage(PACKAGE_ID, PACKAGE_VERSION))
            .compose(v -> cacheManager.isPackageExists(packageName))
            .onComplete(testContext.succeeding(exists -> {
                assertFalse(exists);
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should resolve package name correctly")
    void whenResolvePackageName_thenSucceeds(VertxTestContext testContext) throws IOException {
        byte[] packageContent = loadTestPackage();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(packageContent);

        cacheManager.addPackageToCache(inputStream)
            .compose(npmPackage -> cacheManager.resolveIgPackageName(PACKAGE_ID + "#latest"))
            .onComplete(testContext.succeeding(resolvedName -> {
                assertNotNull(resolvedName);
                assertEquals(PACKAGE_ID, resolvedName.getName());
                assertEquals(PACKAGE_VERSION, resolvedName.getVersion());
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get latest version from packages.fhir.org when internet is available")
    void whenInternetAvailable_thenGetLatestVersionFromFhir(VertxTestContext testContext) {
        // First, check if we can reach packages.fhir.org
        // https://packages2.fhir.org/packages/catalog
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("packages2.fhir.org", 443), 1000);
            System.out.println("Reachable");
        } catch (IOException e) {
            System.out.println("Unreachable");
            testContext.completeNow();
            return;
        }

        String packageId = "hl7.fhir.us.core";
        
        cacheManager.resolveIgPackageName(packageId + "#latest", false)
            .onComplete(testContext.succeeding(resolvedName -> {
                assertNotNull(resolvedName);
                assertEquals(packageId, resolvedName.getName());
                // Version should match a semantic versioning pattern
                assertTrue(resolvedName.getVersion().matches("\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9]+)?"));
                // Version should not be the test package version
                assertNotEquals(PACKAGE_VERSION, resolvedName.getVersion());
                testContext.completeNow();
            }));
    }

    @AfterEach
    void tearDown() {

        if (vertx != null) {
            if(hasPgPool()){
                Pool pool = getPgPool(vertx);
                pool.query("DROP TABLE IF EXISTS %s.fhir_implementation_guides".formatted(DB_POSTGRES_SCHEMA_NAME))
                        .execute()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join();
                pool.close();
                removePgPool();
            }
            vertx.close();
        }
    }
}