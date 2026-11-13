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
     * Names of the keys installed from configuration (spec 008): the command line can't create them (they are rotated
     * through {@code FV_ADMIN_API_KEY} / {@code FV_VALIDATOR_API_KEY}), but may revoke them. Compared ignoring case.
     */
    public static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of(
            nzi.fhir.validator.core.Constant.SYS_ORG_ADMIN_API_KEY_NAME,
            nzi.fhir.validator.core.Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME);
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final long TOUCH_INTERVAL_MS = 60_000;
    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".api_keys";
    private static final String ORGANISATIONS = DB_POSTGRES_SCHEMA_NAME + ".organisations";
    /** Hashes of revoked well-known keys (V13): never installed again. */
    private static final String REVOKED_HASHES = DB_POSTGRES_SCHEMA_NAME + ".revoked_key_hashes";

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

    /** An active key and its organisation: not revoked and (spec 008 FR-006) not expired. */
    private static final String SELECT_ACTIVE = "SELECT k.id, k.role, k.owner, o.name_short, o.is_active, k.name, k.expires_at FROM "
            + TABLE + " k JOIN " + ORGANISATIONS + " o ON o.id = k.owner WHERE k.revoked_at IS NULL "
            + "AND (k.expires_at IS NULL OR k.expires_at > now()) ";

    public Future<Optional<KeyInfo>> findActive(String key) {
        if (!isWellFormed(key)) {
            return Future.succeededFuture(Optional.empty());
        }
        return pool.preparedQuery(SELECT_ACTIVE + "AND k.key_hash = $1")
                .execute(Tuple.of(Buffer.buffer(hash(key))))
                .map(ApiKeyRepository::firstKeyInfo);
    }

    /**
     * The active key {@code id} (spec 009, research R9: the fixed callers, resolved without their unknown value); empty
     * when it doesn't exist, is revoked or has expired.
     */
    public Future<Optional<KeyInfo>> findById(UUID id) {
        return pool.preparedQuery(SELECT_ACTIVE + "AND k.id = $1")
                .execute(Tuple.of(id))
                .map(ApiKeyRepository::firstKeyInfo);
    }

    private static Optional<KeyInfo> firstKeyInfo(io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rows) {
        if (rows.size() == 0) {
            return Optional.empty();
        }
        io.vertx.sqlclient.Row r = rows.iterator().next();
        java.time.OffsetDateTime expires = r.getOffsetDateTime(6);
        return Optional.of(new KeyInfo(r.getUUID(0), r.getString(5), Role.valueOf(r.getString(1)), r.getUUID(2),
                r.getString(3), r.getBoolean(4), expires == null ? null : expires.toInstant()));
    }

    /** Whether {@code name} is reserved for a key installed from configuration (spec 008). */
    public static boolean isReserved(String name) {
        return name != null && RESERVED_NAMES.stream().anyMatch(r -> r.equalsIgnoreCase(name.strip()));
    }

    /**
     * Installs or replaces a key from configuration under a fixed id (spec 008, research R15): the hash, name, role,
     * owner and expiry are overwritten, so restarting is idempotent and a changed value rotates the key in place. A
     * value that was ever revoked is refused (V13).
     *
     * @return whether the key was installed ({@code false}: the value was revoked before; nothing changed); fails with {@link nzi.fhir.validator.core.StartupException} when the key is malformed, or when its name or
     * value is already used by another key (the message names the conflict, never the key)
     */
    public Future<Boolean> upsertWellKnown(UUID id, String name, String key, Role role, UUID owner, java.time.Instant expiresAt) {
        return upsertWellKnown(pool, true, id, name, key, role, owner, expiresAt);
    }

    /**
     * As {@link #upsertWellKnown(UUID, String, String, Role, UUID, java.time.Instant)}, on {@code client} (spec 009: inside
     * the startup access-control transaction, so both keys and the stored mode change together). Concurrent starters
     * serialise on that transaction's settings lock, so no unique-violation retry is needed (or possible: a failed
     * statement aborts the transaction).
     */
    public Future<Boolean> upsertWellKnown(io.vertx.sqlclient.SqlClient client, UUID id, String name, String key, Role role,
                                           UUID owner, java.time.Instant expiresAt) {
        return upsertWellKnown(client, false, id, name, key, role, owner, expiresAt);
    }

    private Future<Boolean> upsertWellKnown(io.vertx.sqlclient.SqlClient client, boolean retry, UUID id, String name, String key,
                                            Role role, UUID owner, java.time.Instant expiresAt) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new nzi.fhir.validator.core.StartupException(
                    "'" + name + "': API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        Tuple params = Tuple.of(id, name, Buffer.buffer(hash(key)), role.name(), owner,
                expiresAt == null ? null : java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        // A value whose hash was revoked is never installed again (V13, code review): an instance still configured with a
        // leaked, revoked value can't bring it back, also after the key was rotated to a new value. A revoked key also
        // stays revoked while the configured value is unchanged; a new value un-revokes it (rotation).
        String sql = "INSERT INTO " + TABLE + " AS k (id, name, key_hash, role, owner, expires_at) "
                + "SELECT $1::uuid, $2::varchar, $3::bytea, $4::varchar, $5::uuid, $6::timestamptz "
                + "WHERE NOT EXISTS (SELECT 1 FROM " + REVOKED_HASHES + " r WHERE r.key_hash = $3::bytea) "
                + "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, "
                + "role = EXCLUDED.role, owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at, "
                + "revoked_at = CASE WHEN k.key_hash = EXCLUDED.key_hash THEN k.revoked_at ELSE NULL END, "
                + "key_hash = EXCLUDED.key_hash";
        return client.preparedQuery(sql).execute(params)
                // Instances starting at once on a database without the row: a concurrent insert can surface as a unique
                // violation on name or key_hash instead of the DO UPDATE path. The row exists now, so one retry updates.
                .recover(err -> retry && nzi.fhir.validator.core.PgErrors.isUniqueViolation(err)
                        ? client.preparedQuery(sql).execute(params) : Future.failedFuture(err))
                .map(rows -> rows.rowCount() > 0)
                .recover(err -> {
                    if (nzi.fhir.validator.core.PgErrors.isUniqueViolation(err)) {
                        String constraint = nzi.fhir.validator.core.PgErrors.constraint(err);
                        boolean value = "api_keys_key_hash_key".equals(constraint);
                        return Future.failedFuture(new nzi.fhir.validator.core.StartupException("Cannot install the '" + name
                                + "' key: another key (revoked keys count too) already uses its " + (value ? "value" : "name")
                                + " (" + constraint + "). " + (value
                                ? "Configure a different value for this key"
                                : "Rename that key in the database (UPDATE fhir_validator_schema.api_keys SET name = '<new name>' "
                                + "WHERE name = '" + name + "')") + ", then restart"));
                    }
                    return Future.failedFuture(err);
                });
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

    /** The id of the key called {@code name}, if any (spec 008: usage summary by key). */
    public Future<Optional<UUID>> findIdByName(String name) {
        return pool.preparedQuery("SELECT id FROM " + TABLE + " WHERE name = $1")
                .execute(Tuple.of(name))
                .map(rows -> rows.size() == 0 ? Optional.<UUID>empty() : Optional.of(rows.iterator().next().getUUID(0)));
    }

    /** The error for a key name that doesn't exist. */
    public static nzi.fhir.validator.web.ProblemException notFound() {
        return new nzi.fhir.validator.web.ProblemException(404, "not-found", "API key not found");
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
                        ? Future.failedFuture(notFound())
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
            // An unknown address keeps the last known one (COALESCE), so the audit trail is never erased.
            pool.preparedQuery("UPDATE " + TABLE + " SET last_used_at = now(), "
                            + "last_used_ip = coalesce($2::text::inet, last_used_ip) WHERE id = $1")
                    .execute(Tuple.of(id, ip));
        }
    }

    /** The throttle decision: records the write when it says yes (atomic per key). */
    boolean shouldTouch(UUID id, String ip, long now) {
        boolean[] write = {false};
        lastTouched.compute(id, (k, previous) -> {
            long since = previous == null ? Long.MAX_VALUE : now - previous.at();
            // An unknown address (null) is never a move: it must not erase the audit address (code review).
            boolean moved = previous != null && ip != null && !ip.equals(previous.ip());
            if (previous == null || since >= TOUCH_INTERVAL_MS || (moved && since >= ADDRESS_CHANGE_INTERVAL_MS)) {
                write[0] = true;
                return new Touched(now, ip != null ? ip : previous == null ? null : previous.ip());
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
