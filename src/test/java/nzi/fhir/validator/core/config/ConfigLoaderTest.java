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
        assertTrue(cfg.anonymousValidate());
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

    @Test
    void igContextSettingsHaveBoundedDefaults() {
        ApplicationConfig cfg = ConfigLoader.load(env("FV_PG_PASSWORD", "pw"));
        assertEquals(4, cfg.igMaxOnDemand());
        // min(10 s, requestTimeout / 2) with the default 60 s request timeout
        assertEquals(10_000, cfg.igBuildWaitMs());
        ApplicationConfig shortTimeout = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_HTTP_REQUEST_TIMEOUT_MS", "6000"));
        assertEquals(3_000, shortTimeout.igBuildWaitMs());
        ApplicationConfig set = ConfigLoader.load(env("FV_PG_PASSWORD", "pw", "FV_IG_MAX_ON_DEMAND", "12",
                "FV_IG_BUILD_WAIT_MS", "30000"));
        assertEquals(12, set.igMaxOnDemand());
        assertEquals(30_000, set.igBuildWaitMs());
        for (String[] bad : new String[][]{{"FV_IG_MAX_ON_DEMAND", "0"}, {"FV_IG_MAX_ON_DEMAND", "65"},
                {"FV_IG_BUILD_WAIT_MS", "999"}, {"FV_IG_BUILD_WAIT_MS", "120001"}}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigLoader.load(env("FV_PG_PASSWORD", "pw", bad[0], bad[1])));
            assertTrue(e.getMessage().contains(bad[0]), e.getMessage());
        }
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
