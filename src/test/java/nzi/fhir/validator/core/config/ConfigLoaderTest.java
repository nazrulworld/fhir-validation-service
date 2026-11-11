package nzi.fhir.validator.core.config;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void classpathDefaultsApplyWhenNothingElseIsSet() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw"));
        assertEquals(8880, cfg.httpPort());
        assertEquals(5432, cfg.pgPort());
        assertEquals("fhir_validator", cfg.pgDatabase());
        assertEquals(20, cfg.httpMaxBodyMb());
        assertTrue(cfg.validationWorkers() >= 1);
        assertEquals(4 * cfg.validationWorkers(), cfg.validationMaxPending());
        assertTrue(cfg.httpInstances() >= 1 && cfg.httpInstances() <= 8);
        assertNull(cfg.json().getJsonObject("auth").getValue("anonymousValidate"), "spec 008: removed");
    }

    @Test
    void fileOverridesClasspathAndEnvOverridesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cfg.json");
        Files.writeString(file, new JsonObject()
                .put("http", new JsonObject().put("port", 9000).put("maxBodyMb", 30))
                .encode());
        ApplicationConfig cfg = ConfigLoader.load(env(
                "FV_CONFIG_FILE", file.toString(),
                "FV_HTTP_PORT", "9100",
                "FV_PG_PASSWORD", "pw"));
        assertEquals(9100, cfg.httpPort(), "env wins over file");
        assertEquals(30, cfg.httpMaxBodyMb(), "file wins over classpath");
    }

    @Test
    void logFormatInConfigFileIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cfg.json");
        Files.writeString(file, new JsonObject().put("log", new JsonObject().put("format", "json")).encode());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_CONFIG_FILE", file.toString(), "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_LOG_FORMAT"), e.getMessage());
    }

    @Test
    void legacyAliasesAreAcceptedButFvNamesWin() {
        ApplicationConfig aliasOnly = ConfigLoader.load(env("PG_HOST", "db1", "PG_PASSWORD", "pw"));
        assertEquals("db1", aliasOnly.pgHost());

        ApplicationConfig both = ConfigLoader.load(env("PG_HOST", "db1", "FV_PG_HOST", "db2", "FV_PG_PASSWORD", "pw"));
        assertEquals("db2", both.pgHost());
    }

    /** Spec 008 FR-014, FR-016: usage sink values, bounds and defaults. */
    @Test
    void usageSettings() {
        ApplicationConfig defaults = ConfigLoader.load(env("FV_PG_PASSWORD", "pw"));
        assertEquals(java.util.Set.of(), defaults.usageSinks());
        assertEquals(10_000, defaults.usageQueueSize());
        assertEquals(500, defaults.usageBatchSize());
        assertEquals(1_000, defaults.usageFlushMs());
        for (String[] ok : new String[][]{{"none", ""}, {"log", "log"}, {"db", "db"}, {"log,db", "db,log"}, {"db, log", "db,log"}}) {
            java.util.Set<String> sinks = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_SINK", ok[0])).usageSinks();
            assertEquals(ok[1].isEmpty() ? java.util.Set.of() : java.util.Set.of(ok[1].split(",")), sinks, ok[0]);
        }
        for (String bad : new String[]{"kafka", "none,db", "log,"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_SINK", bad)), bad);
            assertTrue(e.getMessage().contains("FV_USAGE_SINK"), e.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_QUEUE_SIZE", "99")));
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_QUEUE_SIZE", "1000001")));
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_BATCH_SIZE", "9")));
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_BATCH_SIZE", "5001")));
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_FLUSH_MS", "99")));
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_USAGE_FLUSH_MS", "60001")));
        IllegalStateException cross = assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw",
                "FV_USAGE_QUEUE_SIZE", "100", "FV_USAGE_BATCH_SIZE", "500")));
        assertTrue(cross.getMessage().contains("FV_USAGE_BATCH_SIZE (500) must not exceed FV_USAGE_QUEUE_SIZE (100)"), cross.getMessage());
    }

    /** Spec 008 FR-005: an invalid network in either list fails startup naming the variable and the entry. */
    @Test
    void invalidNetworksFailStartup() {
        IllegalStateException admin = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_ADMIN_CIDRS", "10.0.0.0/8, 10.0.0.0/33")));
        assertTrue(admin.getMessage().contains("invalid FV_AUTH_ADMIN_CIDRS entry '10.0.0.0/33'"), admin.getMessage());
        IllegalStateException proxies = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_TRUSTED_PROXIES", "proxy.local")));
        assertTrue(proxies.getMessage().contains("invalid FV_AUTH_TRUSTED_PROXIES entry 'proxy.local'"), proxies.getMessage());
        ApplicationConfig ok = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_ADMIN_CIDRS", "10.0.0.0/8,::1",
                "FV_AUTH_TRUSTED_PROXIES", "127.0.0.1"));
        assertEquals(2, ok.adminCidrs().networks().size());
        assertEquals(1, ok.trustedProxies().networks().size());
        assertTrue(ConfigLoader.load(env("FV_PG_PASSWORD", "pw")).adminCidrs().isEmpty(), "unset = no restriction");
        assertEquals(nzi.fhir.validator.core.security.ClientAddress.Header.X_FORWARDED_FOR,
                ConfigLoader.load(env("FV_PG_PASSWORD", "pw")).forwardedHeader());
        assertEquals(nzi.fhir.validator.core.security.ClientAddress.Header.FORWARDED,
                ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_FORWARDED_HEADER", "forwarded")).forwardedHeader());
        IllegalStateException header = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_FORWARDED_HEADER", "both")));
        assertTrue(header.getMessage().contains("FV_AUTH_FORWARDED_HEADER"), header.getMessage());
    }

    /** Spec 008 FR-009: the anonymous-validation setting is removed; setting it only logs a warning. */
    @Test
    void anonymousValidateIsIgnoredWithAWarning() {
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_AUTH_ANONYMOUS_VALIDATE", "true"));
            assertNull(cfg.json().getJsonObject("auth").getValue("anonymousValidate"));
            java.util.List<String> warnings = logs.from(ConfigLoader.class.getName()).stream()
                    .filter(e -> e.level() == org.apache.logging.log4j.Level.WARN).map(e -> e.message()).distinct().toList();
            assertEquals(java.util.List.of("FV_AUTH_ANONYMOUS_VALIDATE is deprecated and ignored: removed in 4.1.0, "
                    + "validation always requires a VALIDATE or ADMIN key"), warnings);
        }
    }

    /** Code review regression: the removed setting in a configuration file is ignored with a warning too. */
    @Test
    void anonymousValidateInAConfigFileIsIgnoredWithAWarning(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cfg.json");
        Files.writeString(file, new JsonObject().put("auth", new JsonObject().put("anonymousValidate", true)).encode());
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_CONFIG_FILE", file.toString()));
            assertNull(cfg.json().getJsonObject("auth").getValue("anonymousValidate"));
            assertTrue(logs.from(ConfigLoader.class.getName()).stream().anyMatch(e -> e.level() == org.apache.logging.log4j.Level.WARN
                    && e.message().contains("auth.anonymousValidate") && e.message().contains("ignored")));
        }
    }

    @Test
    void retiredOnDemandSettingsWarnAndAreIgnored() {
        // Spec 007 FR-025: the on-demand settings are gone; a deployment that still sets them starts with one WARN per
        // variable instead of failing, whatever the value (even one the 3.x bounds refused).
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_IG_MAX_ON_DEMAND", "0",
                    "FV_IG_BUILD_WAIT_MS", "30000"));
            assertNotNull(cfg);
            java.util.List<String> warnings = logs.from(ConfigLoader.class.getName()).stream()
                    .filter(e -> e.level() == org.apache.logging.log4j.Level.WARN).map(e -> e.message())
                    // LogCapture sees one event per attached logger config; count distinct messages.
                    .distinct().toList();
            assertEquals(1, warnings.stream().filter(m -> m.contains("FV_IG_MAX_ON_DEMAND")).count(), warnings.toString());
            assertEquals(1, warnings.stream().filter(m -> m.contains("FV_IG_BUILD_WAIT_MS")).count(), warnings.toString());
            assertTrue(warnings.stream().allMatch(m -> m.contains("deprecated") && m.contains("ignored")), warnings.toString());
        }
        try (nzi.fhir.validator.testsupport.LogCapture logs = nzi.fhir.validator.testsupport.LogCapture.start()) {
            ConfigLoader.load(env("FV_PG_PASSWORD", "pw"));
            assertTrue(logs.from(ConfigLoader.class.getName()).stream().noneMatch(e -> e.message().contains("deprecated")));
        }
    }

    @Test
    void terminologySettingsHaveBoundedDefaults() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw"));
        assertEquals(21_600_000L, cfg.txRegistryRefreshMs());
        assertEquals(5_000L, cfg.txTimeoutMs());
        assertEquals(10_000L, cfg.txRequestBudgetMs());
        assertEquals(5_000, cfg.txCacheSize());
        assertEquals(600_000L, cfg.txCacheTtlMs());
        assertEquals(10, cfg.txMaxConnectionsPerServer());
        ApplicationConfig set = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_TX_REGISTRY_REFRESH_MS", "300000",
                "FV_TX_TIMEOUT_MS", "500", "FV_TX_REQUEST_BUDGET_MS", "120000", "FV_TX_CACHE_SIZE", "100",
                "FV_TX_CACHE_TTL_MS", "86400000", "FV_TX_MAX_CONNECTIONS_PER_SERVER", "200"));
        assertEquals(300_000L, set.txRegistryRefreshMs());
        assertEquals(500L, set.txTimeoutMs());
        assertEquals(120_000L, set.txRequestBudgetMs());
        assertEquals(100, set.txCacheSize());
        assertEquals(86_400_000L, set.txCacheTtlMs());
        assertEquals(200, set.txMaxConnectionsPerServer());
        for (String[] bad : new String[][]{
                {"FV_TX_REGISTRY_REFRESH_MS", "299999"}, {"FV_TX_REGISTRY_REFRESH_MS", "604800001"},
                {"FV_TX_TIMEOUT_MS", "499"}, {"FV_TX_TIMEOUT_MS", "60001"},
                {"FV_TX_REQUEST_BUDGET_MS", "999"}, {"FV_TX_REQUEST_BUDGET_MS", "120001"},
                {"FV_TX_CACHE_SIZE", "99"}, {"FV_TX_CACHE_SIZE", "1000001"},
                {"FV_TX_CACHE_TTL_MS", "9999"}, {"FV_TX_CACHE_TTL_MS", "86400001"},
                {"FV_TX_MAX_CONNECTIONS_PER_SERVER", "0"}, {"FV_TX_MAX_CONNECTIONS_PER_SERVER", "201"}}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", bad[0], bad[1])));
            assertTrue(e.getMessage().contains(bad[0]), e.getMessage());
        }
    }

    @Test
    void theRegistryAddressIsNotConfigurable() {
        // Spec 007 FR-006: no variable can change the official registry address.
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_TX_REGISTRY", "http://evil.example",
                "FV_TX_REGISTRY_URL", "http://evil.example"));
        assertFalse(cfg.toString().contains("evil.example"));
    }

    @Test
    void outOfBoundsValueFailsNamingTheVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_HTTP_PORT", "70000", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_HTTP_PORT"), e.getMessage());
    }

    @Test
    void unparseableValueFailsNamingTheVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_VALIDATION_WORKERS", "many", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_VALIDATION_WORKERS"), e.getMessage());
    }

    @Test
    void missingPasswordFailsUnlessDevProfile() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ConfigLoader.load(env()));
        assertTrue(e.getMessage().contains("FV_PG_PASSWORD"), e.getMessage());

        ApplicationConfig dev = ConfigLoader.load(env("FV_PROFILE", "dev"));
        assertTrue(dev.isDev());
    }

    @Test
    void wildcardCorsRefusedInProd() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_CORS_ALLOWED_ORIGINS", "*", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_CORS_ALLOWED_ORIGINS"), e.getMessage());

        ApplicationConfig dev = ConfigLoader.load(env("FV_CORS_ALLOWED_ORIGINS", "*", "FV_PROFILE", "dev"));
        assertEquals(java.util.List.of("*"), dev.corsAllowedOrigins());
    }

    @Test
    void listsAreSplitAndTrimmed() {
        ApplicationConfig cfg = ConfigLoader.load(env(
                "FV_PACKAGE_SOURCES", " a.example , b.example ",
                "FV_PG_PASSWORD", "pw"));
        assertEquals(java.util.List.of("a.example", "b.example"), cfg.packageSources());
    }

    @Test
    void insecurePackageSourcesOnlyInDev() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_PACKAGE_ALLOW_INSECURE_SOURCES", "true", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_PACKAGE_ALLOW_INSECURE_SOURCES"));
        assertTrue(ConfigLoader.load(env("FV_PACKAGE_ALLOW_INSECURE_SOURCES", "true", "FV_PROFILE", "dev")).packageAllowInsecureSources());
    }

    @Test
    void ephemeralPortOnlyInDev() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_HTTP_PORT", "0", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_HTTP_PORT"));
        assertEquals(0, ConfigLoader.load(env("FV_HTTP_PORT", "0", "FV_PROFILE", "dev")).httpPort());
    }

    @Test
    void logFormatIsTextOrJson() {
        assertDoesNotThrow(() -> ConfigLoader.load(env("FV_LOG_FORMAT", "json", "FV_PG_PASSWORD", "pw")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(env("FV_LOG_FORMAT", "xml", "FV_PG_PASSWORD", "pw")));
        assertTrue(e.getMessage().contains("FV_LOG_FORMAT"));
    }

    @Test
    void blankValuesMeanNotSet() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_HTTP_INSTANCES", "", "FV_HTTP_PORT", " ", "FV_PG_PASSWORD", "pw"));
        assertEquals(8880, cfg.httpPort());
        assertTrue(cfg.httpInstances() >= 1);
    }

    @Test
    void passwordIsNeverPartOfToString() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "Sup3rS3cretValue"));
        assertFalse(cfg.toString().contains("Sup3r"));
    }

    // --- spec 005 FR-004: the system organisation from the environment ------------------------------------------

    @Test
    void systemOrganisationVariablesMapToTheirFields() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw",
                "FV_SYSTEM_ORG_NAME_SHORT", "ops",
                "FV_SYSTEM_ORG_NAME_LONG", "Acme Ops",
                "FV_SYSTEM_ORG_EMAIL", "ops@acme.example",
                "FV_SYSTEM_ORG_PHONE", "+45 12 34 56 78",
                "FV_SYSTEM_ORG_WEBSITE", "https://acme.example",
                "FV_SYSTEM_ORG_COUNTRY", "DK",
                "FV_SYSTEM_ORG_POST_CODE", "2100",
                "FV_SYSTEM_ORG_ADDRESS_LINES", "a|b",
                "FV_SYSTEM_ORG_CONTACT_PERSON_NAME", "Jane Doe",
                "FV_SYSTEM_ORG_CONTACT_PERSON_MOBILE", "+45 87 65 43 21",
                "FV_SYSTEM_ORG_CONTACT_PERSON_EMAIL", "jane@acme.example"));
        Map<String, Object> org = cfg.systemOrganisation();
        assertEquals("ops", org.get("nameShort"));
        assertEquals("Acme Ops", org.get("nameLong"));
        assertEquals("ops@acme.example", org.get("email"));
        assertEquals("+45 12 34 56 78", org.get("phone"));
        assertEquals("https://acme.example", org.get("website"));
        assertEquals("DK", org.get("country"));
        assertEquals("2100", org.get("postCode"));
        assertEquals(java.util.List.of("a", "b"), org.get("addressLines"), "lines are separated by |");
        assertEquals("Jane Doe", org.get("contactPersonName"));
        assertEquals("+45 87 65 43 21", org.get("contactPersonMobile"));
        assertEquals("jane@acme.example", org.get("contactPersonEmail"));
        assertEquals(11, org.size());
    }

    @Test
    void unsetSystemOrganisationVariablesAreAbsent() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_SYSTEM_ORG_EMAIL", "ops@acme.example",
                "FV_SYSTEM_ORG_PHONE", ""));
        assertEquals(Map.of("email", "ops@acme.example"), cfg.systemOrganisation(), "an empty variable is not set");
        assertEquals(Map.of(), ConfigLoader.load(env("FV_PG_PASSWORD", "pw")).systemOrganisation());
    }

    @Test
    void invalidSystemOrganisationValuesFailNamingTheVariable() {
        for (String[] bad : new String[][]{
                {"FV_SYSTEM_ORG_COUNTRY", "XX1"},
                {"FV_SYSTEM_ORG_EMAIL", "not-an-address"},
                {"FV_SYSTEM_ORG_NAME_SHORT", "a b"},
                {"FV_SYSTEM_ORG_WEBSITE", "ftp://acme.example"},
                {"FV_SYSTEM_ORG_ADDRESS_LINES", "1|2|3|4|5|6|7"}}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", bad[0], bad[1])), bad[0]);
            assertTrue(e.getMessage().startsWith("Invalid " + bad[0]), e.getMessage());
        }
    }
}
