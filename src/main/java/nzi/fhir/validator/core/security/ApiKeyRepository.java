package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

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

    public enum Role { ADMIN, VALIDATE }

    public record KeyInfo(UUID id, Role role) {
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
        return pool.preparedQuery("SELECT id, role FROM " + TABLE + " WHERE key_hash = $1 AND revoked_at IS NULL")
                .execute(Tuple.of(Buffer.buffer(hash(key))))
                .map(rows -> rows.size() == 0 ? Optional.empty()
                        : Optional.of(new KeyInfo(rows.iterator().next().getUUID(0), Role.valueOf(rows.iterator().next().getString(1)))));
    }

    /**
     * Installs (or replaces) the bootstrap admin key from configuration.
     */
    public Future<Void> upsertBootstrap(String key) {
        return save(BOOTSTRAP_NAME, key, Role.ADMIN);
    }

    /**
     * Creates or replaces the key called {@code name} (un-revoking it).
     */
    public Future<Void> save(String name, String key, Role role) {
        if (!isWellFormed(key)) {
            return Future.failedFuture(new IllegalArgumentException("API keys must be 32-128 characters of [A-Za-z0-9_-]"));
        }
        return pool.preparedQuery("INSERT INTO " + TABLE + " (name, key_hash, role) VALUES ($1, $2, $3) "
                        + "ON CONFLICT (name) DO UPDATE SET key_hash = EXCLUDED.key_hash, role = EXCLUDED.role, revoked_at = NULL")
                .execute(Tuple.of(name, Buffer.buffer(hash(key)), role.name()))
                .mapEmpty();
    }

    public Future<Void> revoke(String name) {
        return pool.preparedQuery("UPDATE " + TABLE + " SET revoked_at = now() WHERE name = $1")
                .execute(Tuple.of(name))
                .mapEmpty();
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
