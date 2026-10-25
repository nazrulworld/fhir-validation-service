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
    void legacyAliasesAreAcceptedButFvNamesWin() {
        ApplicationConfig aliasOnly = ConfigLoader.load(env("PG_HOST", "db1", "PG_PASSWORD", "pw"));
        assertEquals("db1", aliasOnly.pgHost());

        ApplicationConfig both = ConfigLoader.load(env("PG_HOST", "db1", "FV_PG_HOST", "db2", "FV_PG_PASSWORD", "pw"));
        assertEquals("db2", both.pgHost());
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
}
