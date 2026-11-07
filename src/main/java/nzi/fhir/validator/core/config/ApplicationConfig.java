package nzi.fhir.validator.core.config;

import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable, typed view of the merged service configuration (classpath defaults, optional file, environment).
 * Created once at startup by {@link ConfigLoader}.
 *
 * @author Md Nazrul Islam
 */
public final class ApplicationConfig {
    public static final String DB_POSTGRES_SCHEMA_NAME = "fhir_validator_schema";

    private final JsonObject json;

    ApplicationConfig(JsonObject json) {
        this.json = json;
    }

    /**
     * Configuration for tests and tools: classpath defaults merged with {@code overrides}.
     */
    public static ApplicationConfig of(JsonObject overrides) {
        return ConfigLoader.fromJson(overrides);
    }

    public JsonObject json() {
        return json.copy();
    }

    private JsonObject section(String name) {
        return json.getJsonObject(name, new JsonObject());
    }

    static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public String profile() { return json.getString("profile", "prod"); }
    public boolean isDev() { return "dev".equalsIgnoreCase(profile()); }

    public int httpPort() { return section("http").getInteger("port"); }
    public int httpInstances() { return section("http").getInteger("instances"); }
    public long httpRequestTimeoutMs() { return section("http").getLong("requestTimeoutMs"); }
    public int httpMaxBodyMb() { return section("http").getInteger("maxBodyMb"); }
    public List<String> corsAllowedOrigins() {
        return splitList(section("http").getJsonObject("cors", new JsonObject()).getString("allowedOrigins"));
    }

    public int validationWorkers() { return section("validation").getInteger("workers"); }
    public int validationMaxPending() { return section("validation").getInteger("maxPending"); }
    public int validationMaxBundleEntries() { return section("validation").getInteger("maxBundleEntries"); }
    public long validationTimeoutMs() { return section("validation").getLong("timeoutMs"); }

    public int packageWorkers() { return section("packages").getInteger("workers"); }
    public int adminMaxPending() { return section("admin").getInteger("maxPending"); }
    public int packageCacheMaxMb() { return section("packages").getInteger("cacheMaxMb"); }
    /** Raw {@code FV_PACKAGE_SOURCES} entries: hosts ({@code packages.fhir.org}) or base URLs ({@code https://host[:port]}). */
    public List<String> packageSources() { return splitList(section("packages").getString("sources")); }

    /** Host names allowed for package downloads (the host part of every source). */
    public List<String> packageSourceHosts() {
        return packageSources().stream()
                .map(s -> s.contains("://") ? java.net.URI.create(s).getHost() : s)
                .map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .distinct().toList();
    }

    /** Registry base URLs: a bare host means {@code https://<host>}. */
    public List<String> packageRegistryUrls() {
        return packageSources().stream()
                .map(s -> s.contains("://") ? s.replaceAll("/+$", "") : "https://" + s)
                .toList();
    }
    public boolean packageRegistryEnabled() { return section("packages").getBoolean("registryEnabled"); }
    /** Dev only: allow http and private/loopback package sources (tests, local registries). */
    public boolean packageAllowInsecureSources() { return section("packages").getBoolean("allowInsecureSources", false); }
    public long packageFetchTimeoutMs() { return section("packages").getLong("fetchTimeoutMs"); }
    public long packageMaxCompressedMb() { return section("packages").getLong("maxCompressedMb"); }
    public long packageMaxExpandedMb() { return section("packages").getLong("maxExpandedMb"); }
    public int packageMaxEntries() { return section("packages").getInteger("maxEntries"); }
    public int packageMaxRatio() { return section("packages").getInteger("maxRatio"); }

    public long syncPollIntervalMs() { return section("sync").getLong("pollIntervalMs"); }

    /** Most on-demand validator contexts one instance keeps ({@code FV_IG_MAX_ON_DEMAND}, spec 004 FR-015). */
    public int igMaxOnDemand() { return section("ig").getInteger("maxOnDemand"); }

    /** How long a request waits for an on-demand context build ({@code FV_IG_BUILD_WAIT_MS}, spec 004 FR-012). */
    public long igBuildWaitMs() { return section("ig").getLong("buildWaitMs"); }

    public boolean anonymousValidate() { return section("auth").getBoolean("anonymousValidate"); }
    public String bootstrapAdminKey() { return section("auth").getString("bootstrapAdminKey"); }
    public long authCacheTtlMs() { return section("auth").getLong("cacheTtlMs"); }

    public long healthDbStalenessMs() { return section("health").getLong("dbStalenessMs"); }

    public long shutdownPreDrainMs() { return section("shutdown").getLong("preDrainMs"); }
    public long shutdownGraceMs() { return section("shutdown").getLong("graceMs"); }

    /**
     * The configured fields of the system organisation ({@code FV_SYSTEM_ORG_*}, spec 005 FR-004): organisation field
     * name → value, only the fields that are set. Address lines ({@code a|b}) are a {@code List<String>}.
     */
    public java.util.Map<String, Object> systemOrganisation() {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        JsonObject systemOrg = section("systemOrg");
        for (String field : systemOrg.fieldNames()) {
            Object value = systemOrg.getValue(field);
            if (value == null) {
                continue;
            }
            fields.put(field, "addressLines".equals(field)
                    ? List.of(String.valueOf(value).split("\\|", -1)) : String.valueOf(value));
        }
        return fields;
    }

    public String pgHost() { return section("pg").getString("host"); }
    public int pgPort() { return section("pg").getInteger("port"); }
    public String pgDatabase() { return section("pg").getString("database"); }
    public String pgUser() { return section("pg").getString("user"); }
    public String pgPassword() { return section("pg").getString("password"); }
    public int pgPoolSize() { return section("pg").getInteger("poolSize"); }
    public String pgSslMode() { return section("pg").getString("sslMode"); }

    /**
     * Never includes secrets.
     */
    @Override
    public String toString() {
        JsonObject safe = json.copy();
        safe.getJsonObject("pg", new JsonObject()).remove("password");
        safe.getJsonObject("auth", new JsonObject()).remove("bootstrapAdminKey");
        return "ApplicationConfig" + safe.encode();
    }
}
