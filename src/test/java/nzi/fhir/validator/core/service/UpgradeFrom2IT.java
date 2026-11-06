package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrading a 2.0.0 database (schema V6) to 3.0.0 keeps every included IG as a pinned context (spec 004 US4-1,
 * FR-035, SC-010): the service starts on it, builds one context per previously included IG, validates against each
 * through {@code ig}, and uses the core-only base context without {@code ig}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class UpgradeFrom2IT extends BaseTestContainer {
    private static final String S = "fhir_validator_schema";
    private static final String PA = "http://example.org/fhir/StructureDefinition/upgrade-a";
    private static final String PB = "http://example.org/fhir/StructureDefinition/upgrade-b";

    private CoreHarness core;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private static void insertPackage(Connection c, String name, String version, byte[] tgz) throws Exception {
        try (PreparedStatement st = c.prepareStatement("INSERT INTO " + S + ".fhir_implementation_guides "
                + "(ig_package_id, ig_package_version, ig_package_meta, content_raw, dependencies, content_sha256, source, expanded_bytes) "
                + "VALUES (?, ?, ?::jsonb, ?, '{}', ?, 'UPLOAD', ?)")) {
            st.setString(1, name);
            st.setString(2, version);
            st.setString(3, new JsonObject().put("name", name).put("version", version)
                    .put("fhirVersions", new JsonArray().add("4.0.1")).encode());
            st.setBytes(4, tgz);
            st.setBytes(5, MessageDigest.getInstance("SHA-256").digest(tgz));
            st.setLong(6, tgz.length * 10L);
            st.execute();
        }
    }

    @Test
    void includedIgsBecomePinnedContextsAfterTheUpgrade(Vertx vertx, VertxTestContext ctx) throws Exception {
        resetSchema();
        DatabaseService.flywayBuilder(connectOptions()).target(MigrationVersion.fromVersion("6")).load().migrate();
        try (Connection c = DriverManager.getConnection(getPostgresJdbcUrl(), POSTGRES_USERNAME, POSTGRES_PASSWORD)) {
            insertPackage(c, "upgrade.a", "1.0.0", TestPackages.profilePackage("upgrade.a", "1.0.0",
                    TestPackages.requiredPatientElements(PA, "birthDate")));
            insertPackage(c, "upgrade.b", "1.0.0", TestPackages.profilePackage("upgrade.b", "1.0.0",
                    TestPackages.requiredPatientElements(PB, "gender")));
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE " + S + ".fhir_validator_logs SET included_ig_packages = '{upgrade.a#1.0.0,upgrade.b#1.0.0}', "
                        + "resolved_ig_packages = '{upgrade.a#1.0.0,upgrade.b#1.0.0}', revision = 5 WHERE fhir_version = 'R4'");
                st.execute("INSERT INTO " + S + ".api_keys (name, key_hash, role) VALUES ('ops', '\\x09', 'ADMIN')");
            }
        }

        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    core = h;
                    ValidatorRegistry registry = h.services().validatorRegistry();
                    ctx.verify(() -> {
                        for (String ig : List.of("upgrade.a#1.0.0", "upgrade.b#1.0.0")) {
                            SnapshotKey key = SnapshotKey.of(SupportedFhirVersion.R4, ig);
                            assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(key), ig);
                            assertEquals(ValidatorRegistry.Status.ACTIVE, registry.status(key), ig);
                            assertEquals(List.of(ig), registry.current(key).resolvedPackages(), "each IG is its own context");
                        }
                        assertEquals(5, registry.appliedRevision(SupportedFhirVersion.R4), "the revision is kept");
                        assertTrue(registry.isReady());
                    });
                    return Future.all(
                            validate(h, "upgrade.a#1.0.0", PA),
                            validate(h, "upgrade.b#1.0.0", PB),
                            validate(h, null, PA).transform(Future::succeededFuture),
                            h.services().pool().query("SELECT count(*) FROM " + S + ".api_keys WHERE name = 'ops'").execute()
                                    .map(rows -> rows.iterator().next().getLong(0)));
                })
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(all.resultAt(0), "birthDate"));
                    assertTrue(TestPackages.hasError(all.resultAt(1), "gender"));
                    io.vertx.core.AsyncResult<?> base = all.resultAt(2);
                    assertTrue(base.failed(), "without ig the included IG's profile is not available (base context)");
                    assertEquals(1L, (Long) all.resultAt(3), "API keys survive the upgrade");
                    ctx.completeNow();
                })));
    }

    private static Future<nzi.fhir.validator.core.validator.ValidatorSnapshot.Outcome> validate(CoreHarness h, String ig, String profile) {
        return h.services().validationService().validate(SupportedFhirVersion.R4, ig == null ? null : IgReference.parse(ig),
                TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(profile), Function.identity());
    }
}
