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
    /** Spec 009: parsed once (ConfigLoader has already validated the value). */
    private final nzi.fhir.validator.core.enums.ServedVersions servedVersions;

    ApplicationConfig(JsonObject json) {
        this.json = json;
        this.servedVersions = nzi.fhir.validator.core.enums.ServedVersions.parse(
                json.getJsonObject("fhir", new JsonObject()).getString("enabledVersions"));
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

    /** How often the terminology registry's server list is fetched ({@code FV_TX_REGISTRY_REFRESH_MS}, spec 007 FR-007). */
    public long txRegistryRefreshMs() { return section("tx").getLong("registryRefreshMs"); }

    /** Connect and read timeout of one terminology call ({@code FV_TX_TIMEOUT_MS}, spec 007 FR-019). */
    public long txTimeoutMs() { return section("tx").getLong("timeoutMs"); }

    /** Terminology time budget of one validation request ({@code FV_TX_REQUEST_BUDGET_MS}, spec 007 FR-019a). */
    public long txRequestBudgetMs() { return section("tx").getLong("requestBudgetMs"); }

    /** Code results cached per context ({@code FV_TX_CACHE_SIZE}, spec 007 FR-018). */
    public int txCacheSize() { return section("tx").getInteger("cacheSize"); }

    /** Lifetime of a cached code result ({@code FV_TX_CACHE_TTL_MS}, spec 007 FR-018). */
    public long txCacheTtlMs() { return section("tx").getLong("cacheTtlMs"); }

    /** Pooled connections per terminology server ({@code FV_TX_MAX_CONNECTIONS_PER_SERVER}). */
    public int txMaxConnectionsPerServer() { return section("tx").getInteger("maxConnectionsPerServer"); }

    public String bootstrapAdminKey() { return section("auth").getString("bootstrapAdminKey"); }
    /** The trial organisation's shared VALIDATE key ({@code FV_VALIDATOR_API_KEY}, spec 008), or {@code null}. */
    public String validatorKey() { return section("auth").getString("validatorKey"); }
    public long authCacheTtlMs() { return section("auth").getLong("cacheTtlMs"); }

    /** Spec 009: whether API-key access control is enforced ({@code FV_GLOBAL_DISABLED_ACL_SECURITY}). */
    public nzi.fhir.validator.core.security.AclMode aclMode() {
        return Boolean.TRUE.equals(section("auth").getBoolean("globalDisabledAclSecurity"))
                ? nzi.fhir.validator.core.security.AclMode.DISABLED : nzi.fhir.validator.core.security.AclMode.ENABLED;
    }

    /** Spec 009 FR-029: a start may change the stored access-control mode ({@code FV_ACL_MODE_CHANGE_CONFIRMED}). */
    public boolean aclModeChangeConfirmed() {
        return Boolean.TRUE.equals(section("auth").getBoolean("aclModeChangeConfirmed"));
    }

    /** Spec 009: the FHIR versions this instance serves ({@code FV_ENABLED_FHIR_VERSIONS}; unset = all supported). */
    public nzi.fhir.validator.core.enums.ServedVersions servedVersions() {
        return servedVersions;
    }

    /** Spec 009 FR-033: a start may change the stored served versions ({@code FV_FHIR_VERSIONS_CHANGE_CONFIRMED}). */
    public boolean fhirVersionsChangeConfirmed() {
        return Boolean.TRUE.equals(section("fhir").getBoolean("versionsChangeConfirmed"));
    }
    /** Networks administrative operations may come from ({@code FV_AUTH_ADMIN_CIDRS}, spec 008); empty = any. */
    public nzi.fhir.validator.core.security.CidrList adminCidrs() {
        return nzi.fhir.validator.core.security.CidrList.parse(section("auth").getString("adminCidrs"));
    }
    /** The header the trusted proxies write ({@code FV_AUTH_FORWARDED_HEADER}); the other one is never read. */
    public nzi.fhir.validator.core.security.ClientAddress.Header forwardedHeader() {
        return nzi.fhir.validator.core.security.ClientAddress.Header.parse(section("auth").getString("forwardedHeader"));
    }
    /** Proxies whose forwarded headers are believed ({@code FV_AUTH_TRUSTED_PROXIES}, spec 008); empty = none. */
    public nzi.fhir.validator.core.security.CidrList trustedProxies() {
        return nzi.fhir.validator.core.security.CidrList.parse(section("auth").getString("trustedProxies"));
    }

    /** The usage sinks ({@code FV_USAGE_SINK}, spec 008): empty for {@code none}, else {@code log} and/or {@code db}. */
    public java.util.Set<String> usageSinks() { return usageSinks(section("usage").getString("sink")); }
    public int usageQueueSize() { return section("usage").getInteger("queueSize"); }
    public int usageBatchSize() { return section("usage").getInteger("batchSize"); }
    public long usageFlushMs() { return section("usage").getLong("flushMs"); }

    /**
     * Parses {@code FV_USAGE_SINK}: {@code none}, {@code log}, {@code db}, or {@code log,db} in either order.
     *
     * @throws IllegalStateException naming the variable for anything else
     */
    public static java.util.Set<String> usageSinks(String value) {
        String v = value == null || value.isBlank() ? "none" : value.strip().toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> sinks = new java.util.TreeSet<>();
        for (String part : v.split(",", -1)) {
            sinks.add(part.strip());
        }
        if (sinks.equals(java.util.Set.of("none"))) {
            return java.util.Set.of();
        }
        if (sinks.isEmpty() || !java.util.Set.of("log", "db").containsAll(sinks)) {
            throw new IllegalStateException("Invalid FV_USAGE_SINK: '" + value + "' (allowed: none, log, db, log,db)");
        }
        return java.util.Collections.unmodifiableSet(sinks);
    }

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
        safe.getJsonObject("auth", new JsonObject()).remove("validatorKey");
        return "ApplicationConfig" + safe.encode();
    }
}
