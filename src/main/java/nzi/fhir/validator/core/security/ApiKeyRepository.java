package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.org.Organisations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * API keys ({@code api_keys}). Keys are never stored; only their SHA-256 hash.
 */
public class ApiKeyRepository {
    public static final String BOOTSTRAP_NAME = "bootstrap";
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final long TOUCH_INTERVAL_MS = 60_000;
    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".api_keys";
    private static final String ORGANISATIONS = DB_POSTGRES_SCHEMA_NAME + ".organisations";

    public enum Role { ADMIN, VALIDATE }

    /**
     * An active key and its organisation (spec 005 research R3); cached whole by {@link ApiKeyAuthHandler}, so an
     * organisation's deactivation takes effect within the cache TTL.
     */
    public record KeyInfo(UUID id, Role role, UUID ownerId, String ownerShortName, boolean ownerActive) {
        /** The caller this key acts as. */
        public Caller caller() {
            return Caller.of(ownerId, ownerShortName, role);
        }
    }

    /** One row of {@link #list} (never the key or its hash). */
    public record KeySummary(String name, Role role, String org, java.time.Instant createdAt, java.time.Instant lastUsedAt,
                             boolean revoked) {
        public io.vertx.core.json.JsonObject toJson() {
            return new io.vertx.core.json.JsonObject()
                    .put("name", name)
                    .put("role", role.name())
                    .put("org", org)
                    .put("createdAt", createdAt == null ? null : createdAt.toString())
                    .put("lastUsedAt", lastUsedAt == null ? null : lastUsedAt.toString())
                    .put("revoked", revoked);
        }
    }

    private final Pool pool;
    private final Map<UUID, Long> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyRepository(Pool pool) {
        this.pool = pool;
    }

    public static boolean isWellFormed(String key) {
        return key != null && KEY_FORMAT.matcher(key).matches();
    }

    public static byte[] hash(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Future<Optional<KeyInfo>> findActive(String key) {
        if (!isWellFormed(key)) {
            return Future.succeededFuture(Optional.empty());
        }
        return pool.preparedQuery("SELECT k.id, k.role, k.owner, o.name_short, o.is_active FROM " + TABLE + " k "
                        + "JOIN " + ORGANISATIONS + " o ON o.id = k.owner WHERE k.key_hash = $1 AND k.revoked_at IS NULL")
                .execute(Tuple.of(Buffer.buffer(hash(key))))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.empty();
                    }
                    io.vertx.sqlclient.Row r = rows.iterator().next();
                    return Optional.of(new KeyInfo(r.getUUID(0), Role.valueOf(r.getString(1)), r.getUUID(2), r.getString(3),
                            r.getBoolean(4)));
                });
    }

    /**
     * Installs (or replaces) the bootstrap admin key from configuration. It always belongs to the system organisation
     * (FR-006), also when the row existed with another owner.
     */
    public Future<Void> upsertBootstrap(String key) {
        return save(BOOTSTRAP_NAME, key, Role.ADMIN, Organisations.SYSTEM_ID);
    }

    /**
     * Creates or replaces the key called {@code name} (un-revoking it), owned by the system organisation.
     */
    public Future<Void> save(String name, String key, Role role) {
        return save(name, key, role, Organisations.SYSTEM_ID);
    }

    /**
     * Creates or replaces the key called {@code name} (un-revoking it), owned by {@code owner}.
     */
    public Future<Void> save(String name, String key, Role role, UUID owner) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new IllegalArgumentException("API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        return pool.preparedQuery("INSERT INTO " + TABLE + " (name, key_hash, role, owner) VALUES ($1, $2, $3, $4) "
                        + "ON CONFLICT (name) DO UPDATE SET key_hash = EXCLUDED.key_hash, role = EXCLUDED.role, "
                        + "owner = EXCLUDED.owner, revoked_at = NULL")
                .execute(Tuple.of(name, Buffer.buffer(hash(key)), role.name(), owner))
                .mapEmpty();
    }

    /**
     * Creates a new key; unlike {@link #save} it never replaces an existing one.
     *
     * @return fails with 409 {@code conflict} when a key of that name exists (revoked or not)
     */
    public Future<Void> create(String name, String key, Role role, UUID owner) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new nzi.fhir.validator.web.ProblemException(400, "invalid-request",
                    "API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        return pool.preparedQuery("INSERT INTO " + TABLE + " (name, key_hash, role, owner) VALUES ($1, $2, $3, $4) "
                        + "ON CONFLICT DO NOTHING RETURNING id")
                .execute(Tuple.of(name, Buffer.buffer(hash(key)), role.name(), owner))
                .compose(rows -> rows.size() == 1 ? Future.succeededFuture()
                        : Future.failedFuture(new nzi.fhir.validator.web.ProblemException(409,
                        nzi.fhir.validator.web.ProblemException.TYPE_CONFLICT, "An API key with this name already exists")));
    }

    public Future<Void> revoke(String name) {
        return pool.preparedQuery("UPDATE " + TABLE + " SET revoked_at = now() WHERE name = $1")
                .execute(Tuple.of(name))
                .mapEmpty();
    }

    /**
     * Revokes the key called {@code name}.
     *
     * @return the short name of its organisation; fails with 404 {@code not-found} when there is no such key
     */
    public Future<String> revokeNamed(String name) {
        return pool.preparedQuery("UPDATE " + TABLE + " k SET revoked_at = coalesce(k.revoked_at, now()) FROM "
                        + ORGANISATIONS + " o WHERE k.name = $1 AND o.id = k.owner RETURNING o.name_short")
                .execute(Tuple.of(name))
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new nzi.fhir.validator.web.ProblemException(404, "not-found", "API key not found"))
                        : Future.succeededFuture(rows.iterator().next().getString(0)));
    }

    /**
     * Every key, or those of {@code owner}, ordered by name: never the key or its hash.
     */
    public Future<java.util.List<KeySummary>> list(Optional<UUID> owner) {
        String where = owner.isPresent() ? " WHERE k.owner = $1" : "";
        Tuple params = owner.map(Tuple::of).orElseGet(Tuple::tuple);
        return pool.preparedQuery("SELECT k.name, k.role, o.name_short, k.created_at, k.last_used_at, k.revoked_at IS NOT NULL "
                        + "FROM " + TABLE + " k JOIN " + ORGANISATIONS + " o ON o.id = k.owner" + where + " ORDER BY k.name")
                .execute(params)
                .map(rows -> {
                    java.util.List<KeySummary> out = new java.util.ArrayList<>();
                    rows.forEach(r -> {
                        java.time.OffsetDateTime created = r.getOffsetDateTime(3);
                        java.time.OffsetDateTime used = r.getOffsetDateTime(4);
                        out.add(new KeySummary(r.getString(0), Role.valueOf(r.getString(1)), r.getString(2),
                                created == null ? null : created.toInstant(), used == null ? null : used.toInstant(),
                                r.getBoolean(5)));
                    });
                    return out;
                });
    }

    /**
     * Records use of a key, at most once per minute per key; failures are ignored.
     */
    public void touch(UUID id) {
        long now = System.currentTimeMillis();
        Long previous = lastTouched.get(id);
        if (previous != null && now - previous < TOUCH_INTERVAL_MS) {
            return;
        }
        lastTouched.put(id, now);
        pool.preparedQuery("UPDATE " + TABLE + " SET last_used_at = now() WHERE id = $1").execute(Tuple.of(id));
    }
}
