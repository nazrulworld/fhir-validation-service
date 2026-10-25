package nzi.fhir.validator.core.config;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

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

    private enum Kind { INT, LONG, BOOL, STRING }

    private record Entry(String env, String alias, String path, Kind kind, long min, long max) {
        Entry(String env, String path, Kind kind, long min, long max) {
            this(env, null, path, kind, min, max);
        }
    }

    private static final long NO_MIN = Long.MIN_VALUE;
    private static final long NO_MAX = Long.MAX_VALUE;

    private static final List<Entry> ENTRIES = List.of(
            new Entry("FV_PROFILE", "profile", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_HTTP_PORT", "http.port", Kind.INT, 0, 65535), // 0 = ephemeral (tests)
            new Entry("FV_HTTP_INSTANCES", "http.instances", Kind.INT, 1, 64),
            new Entry("FV_HTTP_MAX_BODY_MB", "http.maxBodyMb", Kind.INT, 1, 200),
            new Entry("FV_CORS_ALLOWED_ORIGINS", "http.cors.allowedOrigins", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_VALIDATION_WORKERS", "validation.workers", Kind.INT, 1, 256),
            new Entry("FV_VALIDATION_MAX_PENDING", "validation.maxPending", Kind.INT, 1, 100_000),
            new Entry("FV_VALIDATION_TIMEOUT_MS", "validation.timeoutMs", Kind.LONG, 100, 3_600_000),
            new Entry("FV_PACKAGE_WORKERS", "packages.workers", Kind.INT, 1, 16),
            new Entry("FV_PACKAGE_CACHE_MAX_MB", "packages.cacheMaxMb", Kind.INT, 0, 65_536),
            new Entry("FV_PACKAGE_SOURCES", "packages.sources", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_REGISTRY_ENABLED", "packages.registryEnabled", Kind.BOOL, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_ALLOW_INSECURE_SOURCES", "packages.allowInsecureSources", Kind.BOOL, NO_MIN, NO_MAX),
            new Entry("FV_PACKAGE_FETCH_TIMEOUT_MS", "packages.fetchTimeoutMs", Kind.LONG, 100, 600_000),
            new Entry("FV_PACKAGE_MAX_COMPRESSED_MB", "packages.maxCompressedMb", Kind.LONG, 1, 2_048),
            new Entry("FV_PACKAGE_MAX_EXPANDED_MB", "packages.maxExpandedMb", Kind.LONG, 1, 16_384),
            new Entry("FV_PACKAGE_MAX_ENTRIES", "packages.maxEntries", Kind.INT, 1, 1_000_000),
            new Entry("FV_PACKAGE_MAX_RATIO", "packages.maxRatio", Kind.INT, 1, 10_000),
            new Entry("FV_CONFIG_POLL_INTERVAL_MS", "sync.pollIntervalMs", Kind.LONG, 1_000, 60_000),
            new Entry("FV_AUTH_ANONYMOUS_VALIDATE", "auth.anonymousValidate", Kind.BOOL, NO_MIN, NO_MAX),
            new Entry("FV_ADMIN_API_KEY", "auth.bootstrapAdminKey", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_AUTH_CACHE_TTL_MS", "auth.cacheTtlMs", Kind.LONG, 0, 3_600_000),
            new Entry("FV_READINESS_DB_STALENESS_MS", "health.dbStalenessMs", Kind.LONG, 1_000, 600_000),
            new Entry("FV_SHUTDOWN_PRE_DRAIN_MS", "shutdown.preDrainMs", Kind.LONG, 0, 120_000),
            new Entry("FV_SHUTDOWN_GRACE_MS", "shutdown.graceMs", Kind.LONG, 0, 600_000),
            new Entry("FV_PG_HOST", "PG_HOST", "pg.host", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_PORT", "PG_PORT", "pg.port", Kind.INT, 1, 65535),
            new Entry("FV_PG_DATABASE", "PG_DATABASE", "pg.database", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_USER", "PG_USER", "pg.user", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_PASSWORD", "PG_PASSWORD", "pg.password", Kind.STRING, NO_MIN, NO_MAX),
            new Entry("FV_PG_POOL_SIZE", "PG_POOL_SIZE", "pg.poolSize", Kind.INT, 1, 200),
            new Entry("FV_PG_SSL_MODE", "pg.sslMode", Kind.STRING, NO_MIN, NO_MAX)
    );

    private static final List<String> SSL_MODES = List.of("disable", "prefer", "require", "verify-full");

    private ConfigLoader() {
    }

    /**
     * Loads configuration from the process environment. System properties with the same names take precedence
     * over environment variables, which is convenient for IDE runs and tests.
     */
    public static ApplicationConfig load() {
        Map<String, String> env = new HashMap<>(System.getenv());
        for (Entry e : ENTRIES) {
            putIfPresent(env, e.env());
            if (e.alias() != null) {
                putIfPresent(env, e.alias());
            }
        }
        putIfPresent(env, "FV_CONFIG_FILE");
        return load(env);
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
            deepMerge(merged, readFile(file));
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
                case STRING -> raw;
            };
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid " + name + ": '" + raw + "' is not a valid " + kind.name().toLowerCase());
        }
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
        if (!dev && Boolean.TRUE.equals(section(c, "packages").getBoolean("allowInsecureSources"))) {
            throw new IllegalStateException("Invalid FV_PACKAGE_ALLOW_INSECURE_SOURCES: only allowed when FV_PROFILE=dev");
        }
        String ssl = section(c, "pg").getString("sslMode", "prefer");
        if (!SSL_MODES.contains(ssl)) {
            throw new IllegalStateException("Invalid FV_PG_SSL_MODE: '" + ssl + "' (allowed: " + SSL_MODES + ")");
        }
        String key = section(c, "auth").getString("bootstrapAdminKey");
        if (key != null && !key.isEmpty() && !key.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalStateException("Invalid FV_ADMIN_API_KEY: must be 32-128 characters of [A-Za-z0-9_-]");
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
