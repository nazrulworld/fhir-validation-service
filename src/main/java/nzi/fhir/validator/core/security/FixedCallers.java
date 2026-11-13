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
    private record Cached(KeyInfo info, long expiresAt) {
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
        Cached c = admin;
        if (c != null && c.expiresAt() > System.currentTimeMillis()) {
            return Future.succeededFuture(c.info());
        }
        return load(Constant.SYS_ORG_ADMIN_API_KEY_UUID).onSuccess(info -> admin = cache(info));
    }

    public Future<KeyInfo> validate() {
        Cached c = validate;
        if (c != null && c.expiresAt() > System.currentTimeMillis()) {
            return Future.succeededFuture(c.info());
        }
        return load(Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID).onSuccess(info -> validate = cache(info));
    }

    private Cached cache(KeyInfo info) {
        return ttlMs > 0 ? new Cached(info, System.currentTimeMillis() + ttlMs) : null;
    }

    private Future<KeyInfo> load(UUID id) {
        return repository.findById(id).compose(info -> info.isPresent() ? Future.succeededFuture(info.get())
                : Future.failedFuture(new ProblemException(403, "forbidden",
                "This operation's fixed caller key is revoked; restart to re-create it")));
    }
}
