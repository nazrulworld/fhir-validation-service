package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.AppHarness;
import nzi.fhir.validator.testsupport.Await;
import nzi.fhir.validator.testsupport.Fixtures;
import nzi.fhir.validator.testsupport.TestPackages;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 US3 (FR-017 to FR-019, FR-026, SC-008), end to end on one instance: DK Core 3.5.0 and MedCom Core 4.0.0
 * as two R4 contexts parse their ten common packages once; replacing a package's content gives a new entry, and the
 * old one is released; once no context uses a package its shared definitions are released.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 600, timeUnit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedPackageLifecycleIT extends BaseTestContainer {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String POCD = "hl7.fhir.uv.pocd#0.3.0";
    private AppHarness app;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        JsonObject config = AppHarness.baseConfig(getPostgresHost(), getPostgresPort(), POSTGRES_DATABASE,
                POSTGRES_USERNAME, POSTGRES_PASSWORD);
        config.put("http", config.getJsonObject("http").put("maxBodyMb", 64));
        AppHarness.start(vertx, config)
                .compose(h -> {
                    app = h;
                    return h.awaitReady(90_000);
                })
                .compose(v -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (String id : Fixtures.lockedPackages()) {
                        chain = chain.compose(x -> TestPackages.storePackage(app.bootstrap().services(), Fixtures.lockedPackage(id)));
                    }
                    return chain;
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (app == null ? Future.<Void>succeededFuture() : app.stop()).onComplete(ar -> ctx.completeNow());
    }

    private SharedPackageDefinitions shared() {
        return app.bootstrap().services().snapshotBuilder().sharedDefinitions();
    }

    private Future<HttpResponse<Buffer>> include(String id) {
        String[] p = id.split("#");
        return AppHarness.asAdmin(app.client().post(app.port(), "localhost", "/R4/validator"))
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("igPackageId", p[0]).put("igPackageVersion", p[1]));
    }

    private Future<HttpResponse<Buffer>> exclude(String id) {
        return AppHarness.asAdmin(app.client().delete(app.port(), "localhost", "/R4/validator").addQueryParam("ig", id)).send();
    }

    private Future<Long> built(Vertx vertx, String id) {
        return Await.until(vertx, 300_000, id + " is built", () -> app.bootstrap().services().validatorRegistry()
                .snapshot(SnapshotKey.of(R4, id)).map(s -> true).otherwise(false));
    }

    private Future<String> metrics() {
        return app.client().get(app.port(), "localhost", "/metrics").send().map(HttpResponse::bodyAsString);
    }

    private static double sample(String metrics, String name, String labels) {
        return metrics.lines().filter(l -> l.startsWith(name + "{") && l.contains(labels))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }

    private static SharedPackage.Key key(String id, byte[] archive) throws Exception {
        return new SharedPackage.Key(R4, id, MessageDigest.getInstance("SHA-256").digest(archive));
    }

    /** US3-1, FR-018: the second context reuses the ten common packages (12 entries for 22 package uses). */
    @Test
    @Order(1)
    void theSecondContextReusesTheCommonPackages(Vertx vertx, VertxTestContext ctx) {
        include(Fixtures.DK_CORE_350)
                .compose(r -> built(vertx, Fixtures.DK_CORE_350))
                .compose(ms -> include(Fixtures.MEDCOM_CORE_400))
                .compose(r -> built(vertx, Fixtures.MEDCOM_CORE_400))
                .compose(ms -> metrics())
                .onComplete(ctx.succeeding(m -> ctx.verify(() -> {
                    assertEquals(12, shared().entries(R4), "each package once");
                    assertTrue(sample(m, "fv_packages_shared_lookups_total", "result=\"hit\"") >= 10,
                            "the second build reused the ten common packages");
                    assertEquals(12, sample(m, "fv_packages_shared_lookups_total", "result=\"miss\""), 0.0,
                            "each package parsed once");
                    assertEquals(12, sample(m, "fv_packages_shared_entries", "fhir_version=\"R4\""), 0.0);
                    assertTrue(sample(m, "fv_packages_shared_expanded_bytes", "fhir_version=\"R4\"") > 0);
                    ctx.completeNow();
                })));
    }

    /** US3-2, FR-017: new content under the same name#version is a new entry; the old one is released. */
    @Test
    @Order(2)
    void replacedContentIsANewEntryAndTheOldOneIsReleased(Vertx vertx, VertxTestContext ctx) throws Exception {
        byte[] original = Fixtures.lockedPackage(POCD);
        byte[] replaced = withExtraFile(original);
        SharedPackage.Key oldKey = key(POCD, original);
        SharedPackage.Key newKey = key(POCD, replaced);
        assertTrue(shared().contains(oldKey));
        TestPackages.storePackage(app.bootstrap().services(), replaced)
                .compose(v -> Await.until(vertx, 300_000, "both contexts use the new content", () -> {
                    System.gc();
                    return Future.succeededFuture(shared().contains(newKey) && !shared().contains(oldKey));
                }))
                .compose(ms -> app.client().post(app.port(), "localhost", "/R4/validate")
                        .addQueryParam("ig", Fixtures.MEDCOM_CORE_400).putHeader("Authorization", AppHarness.ADMIN_AUTH)
                        .putHeader("Content-Type", "application/fhir+json")
                        .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}")))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(12, shared().entries(R4), "the replaced content took the old entry's place");
                    ctx.completeNow();
                })));
    }

    /** US3-3, SC-008: one context dropped, the other unchanged; both dropped, the shared memory is released. */
    @Test
    @Order(3)
    void sharedDefinitionsAreReleasedOnceNoContextUsesThem(Vertx vertx, VertxTestContext ctx) {
        exclude(Fixtures.DK_CORE_350)
                .compose(r -> {
                    ctx.verify(() -> assertEquals(200, r.statusCode(), r.bodyAsString()));
                    return app.client().post(app.port(), "localhost", "/R4/validate")
                            .addQueryParam("ig", Fixtures.MEDCOM_CORE_400).putHeader("Authorization", AppHarness.ADMIN_AUTH)
                            .putHeader("Content-Type", "application/fhir+json")
                            .sendBuffer(Buffer.buffer("{\"resourceType\":\"Patient\"}"));
                })
                .compose(r -> {
                    ctx.verify(() -> {
                        assertEquals(200, r.statusCode(), "MedCom Core still validates");
                        assertEquals(12, shared().entries(R4), "MedCom Core still holds all twelve");
                    });
                    return exclude(Fixtures.MEDCOM_CORE_400);
                })
                .compose(r -> Await.until(vertx, 60_000, "every shared entry released", () -> {
                    System.gc();
                    return Future.succeededFuture(shared().entries(R4) == 0);
                }))
                .compose(ms -> metrics())
                .onComplete(ctx.succeeding(m -> ctx.verify(() -> {
                    assertEquals(0, sample(m, "fv_packages_shared_entries", "fhir_version=\"R4\""), 0.0);
                    ctx.completeNow();
                })));
    }

    /** {@code tgz} with one more file in {@code package/other/}: same name#version, different content. */
    private static byte[] withExtraFile(byte[] tgz) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(tgz)));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                TarArchiveEntry copy = new TarArchiveEntry(e.getName());
                copy.setSize(data.length);
                tar.putArchiveEntry(copy);
                tar.write(data);
                tar.closeArchiveEntry();
            }
            byte[] extra = "replaced".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("package/other/spec-010-replaced.txt");
            entry.setSize(extra.length);
            tar.putArchiveEntry(entry);
            tar.write(extra);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    @Test
    @Order(0)
    void theLockedPackagesAreTheTwelve() {
        assertEquals(12, Fixtures.lockedPackages().size());
        assertTrue(List.copyOf(Fixtures.lockedPackages()).contains(POCD));
    }
}
