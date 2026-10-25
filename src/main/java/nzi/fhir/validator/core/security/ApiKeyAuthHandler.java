package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import nzi.fhir.validator.core.security.ApiKeyRepository.KeyInfo;
import nzi.fhir.validator.core.security.ApiKeyRepository.Role;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bearer API-key authorisation (FR-012): administrative operations always need an ADMIN key; validation and read
 * operations are anonymous unless {@code FV_AUTH_ANONYMOUS_VALIDATE=false}, then they need a VALIDATE or ADMIN key.
 * Key lookups are cached for {@code FV_AUTH_CACHE_TTL_MS}, so revocations take effect within that time. The cache
 * holds at most {@value #DEFAULT_MAX_CACHED_KEYS} entries (least recently used evicted first), so callers sending many
 * distinct tokens cannot grow it without bound (constitution Principle VI).
 */
public class ApiKeyAuthHandler {
    public static final String CONTEXT_KEY = "apiKeyRole";
    static final int DEFAULT_MAX_CACHED_KEYS = 10_000;

    private record Cached(Optional<KeyInfo> key, long expiresAt) {
    }

    private final ApiKeyRepository repository;
    private final long cacheTtlMs;
    private final boolean anonymousValidate;
    private final int maxCachedKeys;
    private final LinkedHashMap<String, Cached> cache;

    public ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, boolean anonymousValidate) {
        this(repository, cacheTtlMs, anonymousValidate, DEFAULT_MAX_CACHED_KEYS);
    }

    ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, boolean anonymousValidate, int maxCachedKeys) {
        this.repository = repository;
        this.cacheTtlMs = cacheTtlMs;
        this.anonymousValidate = anonymousValidate;
        this.maxCachedKeys = maxCachedKeys;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > ApiKeyAuthHandler.this.maxCachedKeys;
            }
        };
    }

    /** Number of cached key lookups (tests, sizing). */
    int cachedKeys() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static final java.util.regex.Pattern ADMIN_PATHS =
            java.util.regex.Pattern.compile("^/(igs/(upload|register)|[^/]+/(include-ig|register-profile))/?$");
    private static final java.util.regex.Pattern VALIDATE_PATHS =
            java.util.regex.Pattern.compile("^/([^/]+/validate|igs/[^/]+/[^/]+/(dependencies|conformance))/?$");

    /**
     * Router-level authorisation, mounted before contract validation so unauthenticated calls are refused before
     * their input is examined: administrative operations need ADMIN, validation and read operations follow
     * {@code FV_AUTH_ANONYMOUS_VALIDATE}, everything else (health, readiness, metrics) is open.
     */
    public Handler<RoutingContext> rootHandler() {
        return ctx -> {
            String path = ctx.normalizedPath();
            boolean post = ctx.request().method() == io.vertx.core.http.HttpMethod.POST;
            if (post && ADMIN_PATHS.matcher(path).matches()) {
                authorise(ctx, false, Role.ADMIN);
            } else if (VALIDATE_PATHS.matcher(path).matches()) {
                authorise(ctx, anonymousValidate, Role.VALIDATE, Role.ADMIN);
            } else {
                ctx.next();
            }
        };
    }

    private void authorise(RoutingContext ctx, boolean anonymousAllowed, Role... allowed) {
        String key = bearer(ctx.request().getHeader("Authorization"));
        if (key == null) {
            if (anonymousAllowed) {
                ctx.next();
            } else {
                unauthorized(ctx);
            }
            return;
        }
        lookup(key).onComplete(ar -> {
            if (ar.failed()) {
                ErrorResponder.fail(ctx, ar.cause());
                return;
            }
            Optional<KeyInfo> info = ar.result();
            if (info.isEmpty()) {
                unauthorized(ctx);
                return;
            }
            for (Role role : allowed) {
                if (info.get().role() == role) {
                    ctx.put(CONTEXT_KEY, role.name());
                    repository.touch(info.get().id());
                    ctx.next();
                    return;
                }
            }
            ErrorResponder.fail(ctx, new ProblemException(403, "forbidden", "This API key does not allow this operation"));
        });
    }

    private Future<Optional<KeyInfo>> lookup(String key) {
        String cacheKey = java.util.HexFormat.of().formatHex(ApiKeyRepository.hash(key));
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached cached = cache.get(cacheKey);
            if (cached != null) {
                if (cached.expiresAt() > now) {
                    return Future.succeededFuture(cached.key());
                }
                cache.remove(cacheKey);
            }
        }
        return repository.findActive(key).onSuccess(info -> {
            if (cacheTtlMs > 0) {
                synchronized (cache) {
                    cache.put(cacheKey, new Cached(info, now + cacheTtlMs));
                }
            }
        });
    }

    private static void unauthorized(RoutingContext ctx) {
        ErrorResponder.fail(ctx, new ProblemException(401, "unauthorized", "A valid API key is required (Authorization: Bearer <key>)")
                .withHeader("WWW-Authenticate", "Bearer"));
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String key = header.substring(7).trim();
        return key.isEmpty() ? null : key;
    }
}
