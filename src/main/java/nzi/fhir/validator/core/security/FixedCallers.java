package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.security.ApiKeyRepository.KeyInfo;
import nzi.fhir.validator.core.security.ApiKeyRepository.Role;
import nzi.fhir.validator.web.ProblemException;

import java.util.UUID;

/**
 * The two keys requests run as while access control is disabled (spec 009 FR-018, research R9): the
 * {@code system administrator} key (ADMIN, system organisation) and the {@code trial validator} key (VALIDATE, trial
 * organisation). They are resolved by their fixed ids, never by their (unknown, random) values, and cached for
 * {@code FV_AUTH_CACHE_TTL_MS} like key lookups, so an organisation's deactivation or the key's revocation takes effect
 * within that time. Holds two entries.
 */
public class FixedCallers {
    /** A lookup result, found or not (a revoked key is cached too, so refusals cost no query; code review). */
    private record Cached(java.util.Optional<KeyInfo> info, long expiresAt) {
    }

    private final ApiKeyRepository repository;
    private final long ttlMs;
    private volatile Cached admin;
    private volatile Cached validate;

    public FixedCallers(ApiKeyRepository repository, long ttlMs) {
        this.repository = repository;
        this.ttlMs = ttlMs;
    }

    /** The fixed caller of {@code role}: ADMIN → {@code system administrator}, VALIDATE → {@code trial validator}. */
    public Future<KeyInfo> resolve(Role role) {
        return role == Role.ADMIN ? admin() : validate();
    }

    public Future<KeyInfo> admin() {
        return lookup(admin, Constant.SYS_ORG_ADMIN_API_KEY_UUID, c -> admin = c);
    }

    public Future<KeyInfo> validate() {
        return lookup(validate, Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID, c -> validate = c);
    }

    private Future<KeyInfo> lookup(Cached cached, UUID id, java.util.function.Consumer<Cached> store) {
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return result(cached.info());
        }
        return repository.findById(id).compose(info -> {
            if (ttlMs > 0) {
                // As for key lookups (spec 008 FR-006): a cached key never outlives its expiry.
                long until = System.currentTimeMillis() + ttlMs;
                if (info.isPresent() && info.get().expiresAt() != null) {
                    until = Math.min(until, info.get().expiresAt().toEpochMilli());
                }
                store.accept(new Cached(info, until));
            }
            return result(info);
        });
    }

    private static Future<KeyInfo> result(java.util.Optional<KeyInfo> info) {
        return info.isPresent() ? Future.succeededFuture(info.get())
                : Future.failedFuture(new ProblemException(403, "forbidden",
                "This operation's fixed caller key is revoked; restart to re-create it"));
    }
}
