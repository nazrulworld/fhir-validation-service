package nzi.fhir.validator.core.config;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the single service configuration: classpath {@code config.json} (defaults), then the optional JSON file
 * named by {@code FV_CONFIG_FILE}, then environment variables. Every environment variable in
 * {@code specs/001-service-hardening/contracts/configuration.md} is mapped here; invalid values fail startup with a
 * message naming the variable.
 *
 * @author Md Nazrul Islam
 */
public final class ConfigLoader {
    private static final Logger logger = LogManager.getLogger(ConfigLoader.class);

    /** FLAG (spec 009): true for {@code true}, {@code 1} or {@code yes} in any case; anything else is false. */
    private enum Kind { INT, LONG, BOOL, FLAG, STRING }

    private record Entry(String env, String alias, String path, Kind kind, long min, long max) {
        Entry(String env, String path, Kind kind, long min, long max) {
            this(env, null, path, kind, min, max);
        }
    }

    private static final long NO_MIN = Long.MIN_VALUE;
    private static final long NO_MAX = Long.MAX_VALUE;

    private static final List<Entry> ENTRIES = List.of(
            new Entry("FV_PROFILE", "profile", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_LOG_FORMAT", "log.format", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_HTTP_PORT", "http.port", Kind.INT, 0, 65535), // 0 = ephemeral (tests)
            new Entry("FV_HTTP_INSTANCES", "http.instances", Kind.INT, 1, 64),
            new Entry("FV_HTTP_REQUEST_TIMEOUT_MS", "http.requestTimeoutMs", Kind.LONG, 1_000, 600_000),
            new Entry("FV_HTTP_MAX_BODY_MB", "http.maxBodyMb", Kind.INT, 1, 200),
            new Entry("FV_CORS_ALLOWED_ORIGINS", "http.cors.allowedOrigins", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_VALIDATION_WORKERS", "validation.workers", Kind.INT, 1, 256),
            new Entry("FV_VALIDATION_MAX_PENDING", "validation.maxPending", Kind.INT, 1, 100_000),
            new Entry("FV_VALIDATION_MAX_BUNDLE_ENTRIES", "validation.maxBundleEntries", Kind.INT, 1, 1_000_000),
            new Entry("FV_VALIDATION_TIMEOUT_MS", "validation.timeoutMs", Kind.LONG, 100, 3_600_000),
            new Entry("FV_PACKAGE_WORKERS", "packages.workers", Kind.INT, 1, 16),
            new Entry("FV_ADMIN_MAX_PENDING", "admin.maxPending", Kind.INT, 1, 64),
            new Entry("FV_PACKAGE_CACHE_MAX_MB", "packages.cacheMaxMb", Kind.INT, 0, 65_536),
            new Entry("FV_PACKAGE_SOURCES", "packages.sources", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_REGISTRY_ENABLED", "packages.registryEnabled", Kind.BOOL, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_ALLOW_INSECURE_SOURCES", "packages.allowInsecureSources", Kind.BOOL, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_FETCH_TIMEOUT_MS", "packages.fetchTimeoutMs", Kind.LONG, 100, 600_000),
            new Entry("FV_PACKAGE_MAX_COMPRESSED_MB", "packages.maxCompressedMb", Kind.LONG, 1, 2_048),
            new Entry("FV_PACKAGE_MAX_EXPANDED_MB", "packages.maxExpandedMb", Kind.LONG, 1, 16_384),
            new Entry("FV_PACKAGE_MAX_ENTRIES", "packages.maxEntries", Kind.INT, 1, 1_000_000),
            new Entry("FV_PACKAGE_MAX_RATIO", "packages.maxRatio", Kind.INT, 1, 10_000),
            // Spec 007 (data-model §5): terminology. The registry address is deliberately not configurable (FR-006).
            new Entry("FV_TX_REGISTRY_REFRESH_MS", "tx.registryRefreshMs", Kind.LONG, 300_000, 604_800_000),
            new Entry("FV_TX_TIMEOUT_MS", "tx.timeoutMs", Kind.LONG, 500, 60_000),
            new Entry("FV_TX_REQUEST_BUDGET_MS", "tx.requestBudgetMs", Kind.LONG, 1_000, 120_000),
            new Entry("FV_TX_CACHE_SIZE", "tx.cacheSize", Kind.INT, 100, 1_000_000),
            new Entry("FV_TX_CACHE_TTL_MS", "tx.cacheTtlMs", Kind.LONG, 10_000, 86_400_000),
            new Entry("FV_TX_MAX_CONNECTIONS_PER_SERVER", "tx.maxConnectionsPerServer", Kind.INT, 1, 200),
            new Entry("FV_CONFIG_POLL_INTERVAL_MS", "sync.pollIntervalMs", Kind.LONG, 1_000, 60_000),
            new Entry("FV_ADMIN_API_KEY", "auth.bootstrapAdminKey", Kind.STRING, NO_MIN, NO_MAX),
            // Spec 008 (FR-026): the trial organisation's shared VALIDATE key, installed at every start.
            new Entry("FV_VALIDATOR_API_KEY", "auth.validatorKey", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_AUTH_CACHE_TTL_MS", "auth.cacheTtlMs", Kind.LONG, 0, 3_600_000),
            // Spec 008 (FR-002, FR-003): comma-separated IPv4/IPv6 CIDRs; unset = no restriction / headers ignored.
            new Entry("FV_AUTH_ADMIN_CIDRS", "auth.adminCidrs", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_AUTH_TRUSTED_PROXIES", "auth.trustedProxies", Kind.STRING, NO_MIN, NO_MAX),
            // The one header the trusted proxies write: x-forwarded-for (default) or forwarded.
            new Entry("FV_AUTH_FORWARDED_HEADER", "auth.forwardedHeader", Kind.STRING, NO_MIN, NO_MAX),
            // Spec 009: access control can be turned off for the whole deployment; a change of mode must be confirmed.
            new Entry("FV_GLOBAL_DISABLED_ACL_SECURITY", "auth.globalDisabledAclSecurity", Kind.FLAG, NO_MIN, NO_MAX),
            new Entry("FV_ACL_MODE_CHANGE_CONFIRMED", "auth.aclModeChangeConfirmed", Kind.FLAG, NO_MIN, NO_MAX),
            // Spec 009: the FHIR versions this instance serves (comma-separated; unset = all supported).
            new Entry("FV_ENABLED_FHIR_VERSIONS", "fhir.enabledVersions", Kind.STRING, NO_MIN, NO_MAX),
            // Spec 009 FR-033: a start whose served versions differ from the stored ones must be confirmed.
            new Entry("FV_FHIR_VERSIONS_CHANGE_CONFIRMED", "fhir.versionsChangeConfirmed", Kind.FLAG, NO_MIN, NO_MAX),
            // Spec 008 (FR-014, FR-016): usage accounting.
            new Entry("FV_USAGE_SINK", "usage.sink", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_USAGE_QUEUE_SIZE", "usage.queueSize", Kind.INT, 100, 1_000_000),
            new Entry("FV_USAGE_BATCH_SIZE", "usage.batchSize", Kind.INT, 10, 5_000),
            new Entry("FV_USAGE_FLUSH_MS", "usage.flushMs", Kind.LONG, 100, 60_000),
            new Entry("FV_READINESS_DB_STALENESS_MS", "health.dbStalenessMs", Kind.LONG, 1_000, 600_000),
            new Entry("FV_SHUTDOWN_PRE_DRAIN_MS", "shutdown.preDrainMs", Kind.LONG, 0, 120_000),
            new Entry("FV_SHUTDOWN_GRACE_MS", "shutdown.graceMs", Kind.LONG, 0, 600_000),
            new Entry("FV_PG_HOST", "PG_HOST", "pg.host", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_PORT", "PG_PORT", "pg.port", Kind.INT, 1, 65535),
            new Entry("FV_PG_DATABASE", "PG_DATABASE", "pg.database", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_USER", "PG_USER", "pg.user", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_PASSWORD", "PG_PASSWORD", "pg.password", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_POOL_SIZE", "PG_POOL_SIZE", "pg.poolSize", Kind.INT, 1, 200),
            new Entry("FV_PG_SSL_MODE", "pg.sslMode", Kind.STRING, NO_MIN, NO_MAX),
            // Spec 005 FR-004: fields of the system organisation, applied at every start (research R10).
            new Entry("FV_SYSTEM_ORG_NAME_SHORT", "systemOrg.nameShort", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_NAME_LONG", "systemOrg.nameLong", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_EMAIL", "systemOrg.email", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_PHONE", "systemOrg.phone", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_WEBSITE", "systemOrg.website", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_COUNTRY", "systemOrg.country", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_POST_CODE", "systemOrg.postCode", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_ADDRESS_LINES", "systemOrg.addressLines", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_CONTACT_PERSON_NAME", "systemOrg.contactPersonName", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_CONTACT_PERSON_MOBILE", "systemOrg.contactPersonMobile", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_SYSTEM_ORG_CONTACT_PERSON_EMAIL", "systemOrg.contactPersonEmail", Kind.STRING, NO_MIN, NO_MAX)
    );

    /** Why the on-demand variables are ignored (spec 007 FR-025: on-demand contexts were removed). */
    private static final String ON_DEMAND_REMOVED = "validation contexts are no longer built on demand; "
            + "include IGs with POST /{version}/validator";

    /**
     * Variables of earlier releases that no longer have any effect, with the reason logged for each. A deployment that
     * still sets one starts with a warning ({@code <NAME> is deprecated and ignored: <reason>}) instead of failing.
     */
    static final Map<String, String> DEPRECATED = deprecated();

    private static Map<String, String> deprecated() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("FV_IG_MAX_ON_DEMAND", ON_DEMAND_REMOVED);
        m.put("FV_IG_BUILD_WAIT_MS", ON_DEMAND_REMOVED);
        // Spec 008 FR-009.
        m.put("FV_AUTH_ANONYMOUS_VALIDATE", "removed, validation always requires a VALIDATE or ADMIN key");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Prefix of the system-organisation entries (their path is {@code systemOrg.<organisation field>}). */
    static final String SYSTEM_ORG = "systemOrg";

    private static final List<String> SSL_MODES = List.of("disable", "prefer", "require", "verify-full");

    private ConfigLoader() {
    }

    /**
     * Loads configuration from the process environment. System properties with the same names take precedence
     * over environment variables, which is convenient for IDE runs and tests.
     */
    public static ApplicationConfig load() {
        return load(environment());
    }

    /**
     * The process environment with the system properties of the same names applied on top: what {@link #load()}
     * reads. The command line (spec 005) loads its configuration from it too.
     */
    public static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        for (Entry e : ENTRIES) {
            putIfPresent(env, e.env());
            if (e.alias() != null) {
                putIfPresent(env, e.alias());
            }
        }
        putIfPresent(env, "FV_CONFIG_FILE");
        DEPRECATED.keySet().forEach(name -> putIfPresent(env, name));
        return env;
    }

    private static void putIfPresent(Map<String, String> env, String name) {
        String v = System.getProperty(name);
        if (v != null) {
            env.put(name, v);
        }
    }

    public static ApplicationConfig load(Map<String, String> env) {
        JsonObject merged = classpathDefaults();
        String file = env.get("FV_CONFIG_FILE");
        if (file != null && !file.isBlank()) {
            JsonObject fromFile = readFile(file);
            JsonObject log = fromFile.getJsonObject("log");
            if (log != null && log.containsKey("format")) {
                // Log4j picks its layout before this loader runs, so a file value would be silently ignored (FR-028).
                throw new IllegalStateException("Invalid FV_CONFIG_FILE: log.format cannot be set in a file; use FV_LOG_FORMAT");
            }
            deepMerge(merged, fromFile);
        }
        for (Entry e : ENTRIES) {
            String raw = blankToNull(env.get(e.env()));
            String name = e.env();
            if (raw == null && e.alias() != null) {
                raw = blankToNull(env.get(e.alias()));
                name = e.alias();
            }
            if (raw != null) {
                set(merged, e.path(), parse(name, raw, e.kind()));
            }
        }
        DEPRECATED.forEach((name, reason) -> {
            if (blankToNull(env.get(name)) != null) {
                logger.warn("{} is deprecated and ignored: {}", name, reason);
            }
        });
        return finish(merged);
    }

    /** An empty variable (e.g. {@code FV_HTTP_INSTANCES=} in an env file) means "not set". */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    static ApplicationConfig fromJson(JsonObject overrides) {
        JsonObject merged = classpathDefaults();
        deepMerge(merged, overrides.copy());
        return finish(merged);
    }

    private static ApplicationConfig finish(JsonObject merged) {
        // Spec 008: a config file (or JSON overrides) may still carry the removed setting; warn like the variable.
        JsonObject auth = merged.getJsonObject("auth");
        if (auth != null && auth.containsKey("anonymousValidate")) {
            auth.remove("anonymousValidate");
            logger.warn("auth.anonymousValidate (configuration file) is deprecated and ignored: {}",
                    DEPRECATED.get("FV_AUTH_ANONYMOUS_VALIDATE"));
        }
        normaliseFlags(merged);
        normaliseEnabledVersions(merged);
        applyComputedDefaults(merged);
        validate(merged);
        return new ApplicationConfig(merged);
    }

    private static JsonObject classpathDefaults() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream("config.json")) {
            if (is == null) {
                throw new IllegalStateException("Classpath config.json not found");
            }
            return new JsonObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | DecodeException e) {
            throw new IllegalStateException("Unable to read classpath config.json", e);
        }
    }

    private static JsonObject readFile(String file) {
        try {
            return new JsonObject(Files.readString(Path.of(file)));
        } catch (IOException | DecodeException e) {
            throw new IllegalStateException("Invalid FV_CONFIG_FILE: cannot read JSON from " + file, e);
        }
    }

    private static Object parse(String name, String raw, Kind kind) {
        String v = raw.trim();
        try {
            return switch (kind) {
                case INT -> Integer.parseInt(v);
                case LONG -> Long.parseLong(v);
                case BOOL -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                        throw new NumberFormatException(v);
                    }
                    yield Boolean.parseBoolean(v);
                }
                case FLAG -> flag(name, v);
                case STRING -> raw;
            };
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid " + name + ": '" + raw + "' is not a valid " + kind.name().toLowerCase());
        }
    }

    /**
     * Spec 009 (FR-016, research R8): {@code true}, {@code 1} and {@code yes} (any case) mean true; empty or anything
     * else means false, and an unrecognised non-empty value is logged so a typo doesn't silently keep the default.
     * The value of a flag is not a secret, so it is echoed.
     */
    static boolean flag(String name, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes")) {
            return true;
        }
        if (!v.isEmpty() && !v.equals("false") && !v.equals("0") && !v.equals("no")) {
            logger.warn("{}='{}' is not recognised (true, 1 or yes); treated as false", name, raw.trim());
        }
        return false;
    }

    /** A FLAG may come from a file as a JSON boolean or a string: store it as a boolean (once, so it warns once). */
    private static void normaliseFlags(JsonObject merged) {
        for (Entry e : ENTRIES) {
            if (e.kind() != Kind.FLAG) {
                continue;
            }
            Object v = get(merged, e.path());
            if (v != null && !(v instanceof Boolean)) {
                set(merged, e.path(), flag(e.env(), String.valueOf(v)));
            }
        }
    }

    /**
     * A config file may give {@code fhir.enabledVersions} as a JSON array ({@code ["R4","R5"]}) or a string; anything
     * else is a configuration error naming the variable (code review of PR #72).
     */
    private static void normaliseEnabledVersions(JsonObject merged) {
        JsonObject fhir = merged.getJsonObject("fhir");
        Object v = fhir == null ? null : fhir.getValue("enabledVersions");
        if (v == null || v instanceof String) {
            return;
        }
        if (v instanceof io.vertx.core.json.JsonArray array) {
            List<String> names = new java.util.ArrayList<>();
            for (Object item : array) {
                if (!(item instanceof String name)) {
                    throw new IllegalStateException("Invalid FV_ENABLED_FHIR_VERSIONS: fhir.enabledVersions must list "
                            + "version names (strings)");
                }
                names.add(name);
            }
            fhir.put("enabledVersions", String.join(",", names));
            return;
        }
        throw new IllegalStateException("Invalid FV_ENABLED_FHIR_VERSIONS: fhir.enabledVersions must be a comma-separated "
                + "string or a list of version names");
    }

    private static void applyComputedDefaults(JsonObject c) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        JsonObject http = section(c, "http");
        if (http.getValue("instances") == null) {
            http.put("instances", Math.min(cores, 8));
        }
        JsonObject validation = section(c, "validation");
        if (validation.getValue("workers") == null) {
            validation.put("workers", cores);
        }
        if (validation.getValue("maxPending") == null) {
            validation.put("maxPending", 4 * validation.getInteger("workers"));
        }
    }

    private static void validate(JsonObject c) {
        for (Entry e : ENTRIES) {
            Object v = get(c, e.path());
            if (v == null) {
                continue;
            }
            if (v instanceof Number n && (n.longValue() < e.min() || n.longValue() > e.max())) {
                throw new IllegalStateException("Invalid " + e.env() + ": " + n + " is outside the allowed range "
                        + e.min() + ".." + e.max());
            }
        }
        JsonObject validation = section(c, "validation");
        if (validation.getInteger("maxPending") < validation.getInteger("workers")) {
            throw new IllegalStateException("Invalid FV_VALIDATION_MAX_PENDING: must be >= FV_VALIDATION_WORKERS");
        }
        boolean dev = "dev".equalsIgnoreCase(c.getString("profile", "prod"));
        String password = section(c, "pg").getString("password");
        if (!dev && (password == null || password.isEmpty())) {
            throw new IllegalStateException("Missing FV_PG_PASSWORD: a database password is required unless FV_PROFILE=dev");
        }
        String origins = section(section(c, "http"), "cors").getString("allowedOrigins");
        if (!dev && ApplicationConfig.splitList(origins).contains("*")) {
            throw new IllegalStateException("Invalid FV_CORS_ALLOWED_ORIGINS: '*' is not allowed when FV_PROFILE=prod");
        }
        if (!dev && Integer.valueOf(0).equals(section(c, "http").getInteger("port"))) {
            throw new IllegalStateException("Invalid FV_HTTP_PORT: 0 (ephemeral port) is only allowed when FV_PROFILE=dev");
        }
        if (!dev && Boolean.TRUE.equals(section(c, "packages").getBoolean("allowInsecureSources"))) {
            throw new IllegalStateException("Invalid FV_PACKAGE_ALLOW_INSECURE_SOURCES: only allowed when FV_PROFILE=dev");
        }
        String logFormat = section(c, "log").getString("format", "text");
        if (!logFormat.equals("text") && !logFormat.equals("json")) {
            throw new IllegalStateException("Invalid FV_LOG_FORMAT: '" + logFormat + "' (allowed: text, json)");
        }
        String ssl = section(c, "pg").getString("sslMode", "prefer");
        if (!SSL_MODES.contains(ssl)) {
            throw new IllegalStateException("Invalid FV_PG_SSL_MODE: '" + ssl + "' (allowed: " + SSL_MODES + ")");
        }
        // Spec 009 FR-003: a name that isn't a served FHIR version stops the start, naming it.
        nzi.fhir.validator.core.enums.ServedVersions.parse(section(c, "fhir").getString("enabledVersions"));
        // Spec 009 FR-022, research R13: while access control is disabled the key values are ignored, so not checked.
        boolean aclDisabled = Boolean.TRUE.equals(section(c, "auth").getBoolean("globalDisabledAclSecurity"));
        String key = section(c, "auth").getString("bootstrapAdminKey");
        if (!aclDisabled && key != null && !key.isEmpty() && !nzi.fhir.validator.core.security.ApiKeyRepository.isWellFormed(key)) {
            throw new IllegalStateException("Invalid FV_ADMIN_API_KEY: must be 32-128 characters of [A-Za-z0-9_-]");
        }
        for (String[] cidrs : new String[][]{{"FV_AUTH_ADMIN_CIDRS", "adminCidrs"}, {"FV_AUTH_TRUSTED_PROXIES", "trustedProxies"}}) {
            String value = section(c, "auth").getString(cidrs[1]);
            try {
                nzi.fhir.validator.core.security.CidrList.parse(value);
            } catch (IllegalArgumentException e) {
                // "invalid network '<entry>'" → "Invalid FV_AUTH_ADMIN_CIDRS: invalid FV_AUTH_ADMIN_CIDRS entry '<entry>'"
                throw new IllegalStateException("Invalid " + cidrs[0] + ": " + e.getMessage().replace("invalid network",
                        "invalid " + cidrs[0] + " entry"));
            }
        }
        try {
            nzi.fhir.validator.core.security.ClientAddress.Header.parse(section(c, "auth").getString("forwardedHeader"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid FV_AUTH_FORWARDED_HEADER: " + e.getMessage());
        }
        JsonObject usage = section(c, "usage");
        ApplicationConfig.usageSinks(usage.getString("sink"));
        if (usage.getInteger("batchSize", 500) > usage.getInteger("queueSize", 10_000)) {
            throw new IllegalStateException("Invalid FV_USAGE_BATCH_SIZE: FV_USAGE_BATCH_SIZE (" + usage.getInteger("batchSize")
                    + ") must not exceed FV_USAGE_QUEUE_SIZE (" + usage.getInteger("queueSize", 10_000) + ")");
        }
        String validatorKey = section(c, "auth").getString("validatorKey");
        if (!aclDisabled && validatorKey != null && !validatorKey.isEmpty()) {
            if (!nzi.fhir.validator.core.security.ApiKeyRepository.isWellFormed(validatorKey)) {
                throw new IllegalStateException("Invalid FV_VALIDATOR_API_KEY: must be 32-128 characters of [A-Za-z0-9_-]");
            }
            if (validatorKey.equals(key)) {
                throw new IllegalStateException("Invalid FV_VALIDATOR_API_KEY: must differ from FV_ADMIN_API_KEY");
            }
        }
        validateSystemOrganisation(section(c, SYSTEM_ORG));
    }

    /**
     * The system-organisation fields follow the organisation rules (spec 005 FR-004, research R10): an invalid value
     * fails start-up naming the variable, like {@code FV_ADMIN_API_KEY}.
     */
    private static void validateSystemOrganisation(JsonObject systemOrg) {
        for (Entry e : ENTRIES) {
            if (!e.path().startsWith(SYSTEM_ORG + ".")) {
                continue;
            }
            String field = e.path().substring(SYSTEM_ORG.length() + 1);
            Object value = systemOrg.getValue(field);
            if (value == null) {
                continue;
            }
            try {
                nzi.fhir.validator.core.org.Organisation.empty().with(field, String.valueOf(value))
                        .validateFields(java.util.Set.of(field));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Invalid " + e.env() + ": " + ex.getMessage());
            }
        }
        for (String field : systemOrg.fieldNames()) {
            if (ENTRIES.stream().noneMatch(e -> e.path().equals(SYSTEM_ORG + "." + field))) {
                throw new IllegalStateException("Invalid configuration: unknown systemOrg field '" + field + "'");
            }
        }
    }

    private static JsonObject section(JsonObject parent, String name) {
        JsonObject s = parent.getJsonObject(name);
        if (s == null) {
            s = new JsonObject();
            parent.put(name, s);
        }
        return s;
    }

    private static Object get(JsonObject root, String path) {
        String[] parts = path.split("\\.");
        JsonObject cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            cur = cur.getJsonObject(parts[i]);
            if (cur == null) {
                return null;
            }
        }
        return cur.getValue(parts[parts.length - 1]);
    }

    private static void set(JsonObject root, String path, Object value) {
        String[] parts = path.split("\\.");
        JsonObject cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            cur = section(cur, parts[i]);
        }
        cur.put(parts[parts.length - 1], value);
    }

    private static void deepMerge(JsonObject target, JsonObject source) {
        for (String key : source.fieldNames()) {
            Object sv = source.getValue(key);
            Object tv = target.getValue(key);
            if (sv instanceof JsonObject so && tv instanceof JsonObject to) {
                deepMerge(to, so);
            } else {
                target.put(key, sv);
            }
        }
    }
}
