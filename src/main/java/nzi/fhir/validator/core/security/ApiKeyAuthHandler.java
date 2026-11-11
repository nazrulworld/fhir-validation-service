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
 * Bearer API-key authorisation (FR-012): administrative operations always need an ADMIN key; validation and the package
 * read operations need a VALIDATE or ADMIN key (spec 008: anonymous validation was removed in 4.1.0).
 * Key lookups are cached for {@code FV_AUTH_CACHE_TTL_MS}, so revocations take effect within that time. The cache
 * holds at most {@value #DEFAULT_MAX_CACHED_KEYS} entries (least recently used evicted first), so callers sending many
 * distinct tokens cannot grow it without bound (constitution Principle VI).
 */
public class ApiKeyAuthHandler {
    public static final String CONTEXT_KEY = "apiKeyRole";
    /** The request's {@link Caller} (spec 005): set for authenticated and anonymous requests. */
    public static final String CALLER_KEY = "caller";

    /**
     * The caller of a request. A request the handler didn't authenticate (an open path) is anonymous: the default
     * grants the least, never the most.
     */
    public static Caller caller(RoutingContext ctx) {
        Caller c = ctx.get(CALLER_KEY);
        return c == null ? Caller.ANONYMOUS : c;
    }
    static final int DEFAULT_MAX_CACHED_KEYS = 10_000;

    private record Cached(Optional<KeyInfo> key, long expiresAt) {
    }

    private final ApiKeyRepository repository;
    private final long cacheTtlMs;
    private final CidrList adminCidrs;
    private final int maxCachedKeys;
    private final LinkedHashMap<String, Cached> cache;

    public ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs) {
        this(repository, cacheTtlMs, CidrList.EMPTY);
    }

    /**
     * @param adminCidrs networks administrative operations may come from ({@code FV_AUTH_ADMIN_CIDRS}); empty = any
     */
    public ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, CidrList adminCidrs) {
        this(repository, cacheTtlMs, adminCidrs, DEFAULT_MAX_CACHED_KEYS);
    }

    ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, int maxCachedKeys) {
        this(repository, cacheTtlMs, CidrList.EMPTY, maxCachedKeys);
    }

    ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, CidrList adminCidrs, int maxCachedKeys) {
        this.repository = repository;
        this.cacheTtlMs = cacheTtlMs;
        this.adminCidrs = adminCidrs;
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
            java.util.regex.Pattern.compile("^/(igs/(upload|register)|igs/[^/]+/[^/]+|[^/]+/validator)/?$");
    private static final java.util.regex.Pattern VALIDATE_PATHS =
            java.util.regex.Pattern.compile("^/([^/]+/validate|igs/[^/]+/[^/]+/(dependencies|conformance))/?$");

    /**
     * Whether the request is an administrative operation: one that changes IGs or validator configuration. Every
     * method on an administrative path counts (POST, PATCH and DELETE {@code /{version}/validator}, named
     * {@code /{version}/include-ig} before 4.0.0; POST upload/register; PATCH {@code /igs/{name}/{version}}), except
     * {@code OPTIONS} (CORS preflight), so a new method on these paths can never fall through unauthenticated.
     */
    public static boolean isAdministrative(RoutingContext ctx) {
        return ctx.request().method() != io.vertx.core.http.HttpMethod.OPTIONS
                && ADMIN_PATHS.matcher(ctx.normalizedPath()).matches();
    }

    /**
     * Router-level authorisation, mounted before contract validation so unauthenticated calls are refused before
     * their input is examined: administrative operations need ADMIN, validation and read operations need VALIDATE or
     * ADMIN, everything else (health, readiness, metrics) is open.
     */
    public Handler<RoutingContext> rootHandler() {
        return ctx -> {
            if (isAdministrative(ctx)) {
                // Spec 008 FR-002: decided before the key is read, so it holds for every key and costs no lookup.
                if (!adminCidrs.isEmpty() && !adminCidrs.contains(ClientAddress.of(ctx))) {
                    ErrorResponder.fail(ctx, new ProblemException(403, "forbidden",
                            "Administrative operations are not allowed from this network"));
                    return;
                }
                authorise(ctx, Role.ADMIN);
            } else if (VALIDATE_PATHS.matcher(ctx.normalizedPath()).matches()) {
                authorise(ctx, Role.VALIDATE, Role.ADMIN);
            } else {
                ctx.next();
            }
        };
    }

    private void authorise(RoutingContext ctx, Role... allowed) {
        String key = bearer(ctx.request().getHeader("Authorization"));
        // Spec 008 FR-001: a value that can't be a key costs no hashing, cache slot or database query.
        if (key == null || !ApiKeyRepository.isWellFormed(key)) {
            unauthorized(ctx);
            return;
        }
        lookup(key).onComplete(ar -> {
            if (ar.failed()) {
                ErrorResponder.fail(ctx, schemaNotReady(ar.cause())
                        ? new ProblemException(503, "not-ready", "The service is still starting; retry shortly")
                        .withHeader("Retry-After", "5") : ar.cause());
                return;
            }
            Optional<KeyInfo> info = ar.result();
            if (info.isEmpty()) {
                unauthorized(ctx);
                return;
            }
            if (!info.get().ownerActive()) {
                // FR-014: the key is valid, but its organisation is deactivated.
                ErrorResponder.fail(ctx, new ProblemException(403, "forbidden", "The organisation of this API key is deactivated"));
                return;
            }
            for (Role role : allowed) {
                if (info.get().role() == role) {
                    ctx.put(CONTEXT_KEY, role.name());
                    ctx.put(CALLER_KEY, info.get().caller());
                    repository.touch(info.get().id(), ClientAddress.of(ctx));
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
                // Spec 008 FR-006: a cached key never outlives its expiry.
                long until = now + cacheTtlMs;
                if (info.isPresent() && info.get().expiresAt() != null) {
                    until = Math.min(until, info.get().expiresAt().toEpochMilli());
                }
                synchronized (cache) {
                    cache.put(cacheKey, new Cached(info, until));
                }
            }
        });
    }

    /**
     * HTTP listens while warm-up still migrates the database (code review): a missing column or table (SQLState 42703,
     * 42P01) means the schema isn't there yet, which is "not ready" (503), not an internal error.
     */
    static boolean schemaNotReady(Throwable err) {
        return err instanceof io.vertx.pgclient.PgException pg
                && ("42703".equals(pg.getSqlState()) || "42P01".equals(pg.getSqlState()));
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
