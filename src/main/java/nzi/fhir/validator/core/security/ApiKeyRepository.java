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
import java.util.regex.Pattern;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * API keys ({@code api_keys}). Keys are never stored; only their SHA-256 hash.
 */
public class ApiKeyRepository {
    /**
     * Names of the keys installed from configuration (spec 008): the command line can't create or revoke them; they are
     * rotated or revoked through {@code FV_ADMIN_API_KEY} / {@code FV_VALIDATOR_API_KEY}. Compared ignoring case.
     */
    public static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of(
            nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_NAME,
            nzi.fhir.validator.core.Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME);
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final long TOUCH_INTERVAL_MS = 60_000;
    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".api_keys";
    private static final String ORGANISATIONS = DB_POSTGRES_SCHEMA_NAME + ".organisations";

    public enum Role { ADMIN, VALIDATE }

    /**
     * An active key and its organisation (spec 005 research R3); cached whole by {@link ApiKeyAuthHandler}, so an
     * organisation's deactivation takes effect within the cache TTL. {@code expiresAt} (spec 008) caps how long the
     * cache may keep it; {@code null} = never expires.
     */
    public record KeyInfo(UUID id, String name, Role role, UUID ownerId, String ownerShortName, boolean ownerActive,
                          java.time.Instant expiresAt) {
        /** The caller this key acts as. */
        public Caller caller() {
            return Caller.of(ownerId, ownerShortName, role, id, name);
        }
    }

    /** One row of {@link #list} (never the key or its hash). */
    public record KeySummary(String name, Role role, String org, java.time.Instant createdAt, java.time.Instant lastUsedAt,
                             boolean revoked, java.time.Instant expiresAt, String lastUsedIp) {
        public io.vertx.core.json.JsonObject toJson() {
            return new io.vertx.core.json.JsonObject()
                    .put("name", name)
                    .put("role", role.name())
                    .put("org", org)
                    .put("createdAt", createdAt == null ? null : createdAt.toString())
                    .put("lastUsedAt", lastUsedAt == null ? null : lastUsedAt.toString())
                    .put("revoked", revoked)
                    // Spec 008: the expiry and the client address the key was last used from (spot a stolen key).
                    .put("expiresAt", expiresAt == null ? null : expiresAt.toString())
                    .put("lastUsedIp", lastUsedIp);
        }
    }

    /** The last {@link #touch} written by this instance for a key. */
    private record Touched(long at, String ip) {
    }

    /** Bound of the touch throttle (constitution VI: no unbounded map). */
    static final int MAX_TOUCHED_KEYS = 10_000;
    /**
     * A key used from a new address is written at once, but at most once per this interval: a key shared by many
     * clients (the trial key, a NAT pool) would otherwise cost one write of the same row per request.
     */
    static final long ADDRESS_CHANGE_INTERVAL_MS = 1_000;

    private final Pool pool;
    private final Map<UUID, Touched> lastTouched = new java.util.concurrent.ConcurrentHashMap<>();

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
        return pool.preparedQuery("SELECT k.id, k.role, k.owner, o.name_short, o.is_active, k.name, k.expires_at FROM " + TABLE + " k "
                        + "JOIN " + ORGANISATIONS + " o ON o.id = k.owner WHERE k.key_hash = $1 AND k.revoked_at IS NULL "
                        // Spec 008 FR-006: an expired key is not active.
                        + "AND (k.expires_at IS NULL OR k.expires_at > now())")
                .execute(Tuple.of(Buffer.buffer(hash(key))))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.empty();
                    }
                    io.vertx.sqlclient.Row r = rows.iterator().next();
                    java.time.OffsetDateTime expires = r.getOffsetDateTime(6);
                    return Optional.of(new KeyInfo(r.getUUID(0), r.getString(5), Role.valueOf(r.getString(1)), r.getUUID(2),
                            r.getString(3), r.getBoolean(4), expires == null ? null : expires.toInstant()));
                });
    }

    /** Whether {@code name} is reserved for a key installed from configuration (spec 008). */
    public static boolean isReserved(String name) {
        return name != null && RESERVED_NAMES.stream().anyMatch(r -> r.equalsIgnoreCase(name.strip()));
    }

    /**
     * Installs (or replaces) the bootstrap admin key from configuration under its well-known id and name (spec 008).
     * It always belongs to the system organisation (spec 005 FR-006), also when the row existed with another owner.
     */
    public Future<Void> upsertBootstrap(String key) {
        return upsertWellKnown(nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_UUID,
                nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_NAME, key, Role.ADMIN, Organisations.SYSTEM_ID,
                nzi.fhir.validator.core.Constant.SYS_ORG_API_KEY_EXPIRES_AT);
    }

    /**
     * Installs or replaces a key from configuration under a fixed id (spec 008, research R15): the hash, name, role,
     * owner and expiry are overwritten and the key is un-revoked, so restarting is idempotent and a changed value
     * rotates the key in place.
     *
     * @return fails with {@link nzi.fhir.validator.core.StartupException} when the key is malformed, or when its name or
     * value is already used by another key (the message names the conflict, never the key)
     */
    public Future<Void> upsertWellKnown(UUID id, String name, String key, Role role, UUID owner, java.time.Instant expiresAt) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new nzi.fhir.validator.core.StartupException(
                    "'" + name + "': API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        return pool.preparedQuery("INSERT INTO " + TABLE + " (id, name, key_hash, role, owner, expires_at) "
                        + "VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, "
                        + "key_hash = EXCLUDED.key_hash, role = EXCLUDED.role, owner = EXCLUDED.owner, "
                        + "expires_at = EXCLUDED.expires_at, revoked_at = NULL")
                .execute(Tuple.of(id, name, Buffer.buffer(hash(key)), role.name(), owner,
                        expiresAt == null ? null : java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC)))
                .<Void>mapEmpty()
                .recover(err -> {
                    if (err instanceof io.vertx.pgclient.PgException pg && "23505".equals(pg.getSqlState())) {
                        return Future.failedFuture(new nzi.fhir.validator.core.StartupException("Cannot install the '" + name
                                + "' key: another key already uses its " + ("api_keys_key_hash_key".equals(pg.getConstraint())
                                ? "value" : "name") + " (" + pg.getConstraint() + "); rename or revoke that key, then restart"));
                    }
                    return Future.failedFuture(err);
                });
    }

    /**
     * Revokes the key with the fixed id {@code id}, if it exists and is active (spec 008): a key installed from
     * configuration whose variable is no longer set must stop working, or unsetting the variable wouldn't revoke it.
     *
     * @return whether a key was revoked
     */
    public Future<Boolean> revokeWellKnown(UUID id) {
        return pool.preparedQuery("UPDATE " + TABLE + " SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0);
    }

    /**
     * Creates a new key; unlike {@link #save} it never replaces an existing one.
     *
     * @return fails with 409 {@code conflict} when a key of that name exists (revoked or not)
     */
    public Future<Void> create(String name, String key, Role role, UUID owner) {
        return create(name, key, role, owner, null);
    }

    /**
     * Like {@link #create(String, String, Role, UUID)}, with an expiry (spec 008 FR-006; {@code null} = never).
     */
    public Future<Void> create(String name, String key, Role role, UUID owner, java.time.Instant expiresAt) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new nzi.fhir.validator.web.ProblemException(400, "invalid-request",
                    "API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        return pool.preparedQuery("INSERT INTO " + TABLE + " (name, key_hash, role, owner, expires_at) VALUES ($1, $2, $3, $4, $5) "
                        + "ON CONFLICT DO NOTHING RETURNING id")
                .execute(Tuple.of(name, Buffer.buffer(hash(key)), role.name(), owner,
                        expiresAt == null ? null : java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC)))
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
        return pool.preparedQuery("SELECT k.name, k.role, o.name_short, k.created_at, k.last_used_at, k.revoked_at IS NOT NULL, "
                        + "k.expires_at, host(k.last_used_ip) "
                        + "FROM " + TABLE + " k JOIN " + ORGANISATIONS + " o ON o.id = k.owner" + where + " ORDER BY k.name")
                .execute(params)
                .map(rows -> {
                    java.util.List<KeySummary> out = new java.util.ArrayList<>();
                    rows.forEach(r -> {
                        java.time.OffsetDateTime created = r.getOffsetDateTime(3);
                        java.time.OffsetDateTime used = r.getOffsetDateTime(4);
                        java.time.OffsetDateTime expires = r.getOffsetDateTime(6);
                        out.add(new KeySummary(r.getString(0), Role.valueOf(r.getString(1)), r.getString(2),
                                created == null ? null : created.toInstant(), used == null ? null : used.toInstant(),
                                r.getBoolean(5), expires == null ? null : expires.toInstant(), r.getString(7)));
                    });
                    return out;
                });
    }

    /**
     * Records use of a key and the client address it came from (spec 008 FR-007): at most once per minute per key, and
     * at once (but at most once per {@value #ADDRESS_CHANGE_INTERVAL_MS} ms) when the address changed, so a key used
     * from a new place shows up immediately without a shared key writing its row on every request. Lock-free on the
     * request path. Failures are ignored.
     */
    public void touch(UUID id, java.net.InetAddress client) {
        if (shouldTouch(id, client == null ? null : client.getHostAddress(), System.currentTimeMillis())) {
            String ip = client == null ? null : client.getHostAddress();
            pool.preparedQuery("UPDATE " + TABLE + " SET last_used_at = now(), last_used_ip = $2::text::inet WHERE id = $1")
                    .execute(Tuple.of(id, ip));
        }
    }

    /** The throttle decision: records the write when it says yes (atomic per key). */
    boolean shouldTouch(UUID id, String ip, long now) {
        boolean[] write = {false};
        lastTouched.compute(id, (k, previous) -> {
            long since = previous == null ? Long.MAX_VALUE : now - previous.at();
            boolean moved = previous != null && !java.util.Objects.equals(previous.ip(), ip);
            if (previous == null || since >= TOUCH_INTERVAL_MS || (moved && since >= ADDRESS_CHANGE_INTERVAL_MS)) {
                write[0] = true;
                return new Touched(now, ip);
            }
            return previous;
        });
        if (write[0] && lastTouched.size() > MAX_TOUCHED_KEYS) {
            // Rare: drop some other entry (it is only a throttle; the worst case is one extra write for that key).
            java.util.Iterator<UUID> it = lastTouched.keySet().iterator();
            while (lastTouched.size() > MAX_TOUCHED_KEYS && it.hasNext()) {
                UUID other = it.next();
                if (!other.equals(id)) {
                    it.remove();
                }
            }
        }
        return write[0];
    }

    /** Entries in the touch throttle (tests, sizing). */
    int touchedKeys() {
        return lastTouched.size();
    }
}
