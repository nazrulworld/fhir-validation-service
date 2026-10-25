package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import nzi.fhir.validator.core.security.ApiKeyRepository.KeyInfo;
import nzi.fhir.validator.core.security.ApiKeyRepository.Role;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer API-key authorisation (FR-012): administrative operations always need an ADMIN key; validation and read
 * operations are anonymous unless {@code FV_AUTH_ANONYMOUS_VALIDATE=false}, then they need a VALIDATE or ADMIN key.
 * Key lookups are cached for {@code FV_AUTH_CACHE_TTL_MS}, so revocations take effect within that time.
 */
public class ApiKeyAuthHandler {
    public static final String CONTEXT_KEY = "apiKeyRole";

    private record Cached(Optional<KeyInfo> key, long expiresAt) {
    }

    private final ApiKeyRepository repository;
    private final long cacheTtlMs;
    private final boolean anonymousValidate;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ApiKeyAuthHandler(ApiKeyRepository repository, long cacheTtlMs, boolean anonymousValidate) {
        this.repository = repository;
        this.cacheTtlMs = cacheTtlMs;
        this.anonymousValidate = anonymousValidate;
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
        Cached cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return Future.succeededFuture(cached.key());
        }
        return repository.findActive(key).onSuccess(info -> {
            if (cacheTtlMs > 0) {
                cache.put(cacheKey, new Cached(info, now + cacheTtlMs));
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
