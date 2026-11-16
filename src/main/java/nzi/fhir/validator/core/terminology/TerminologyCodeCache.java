package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.support.IValidationSupport;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The one code-result cache of an instance (spec 010 FR-012, FR-013, research R4, data-model §6). It replaces HAPI's
 * per-snapshot code caching: a key includes the snapshot (a result can depend on the context's own code systems and
 * value sets) and the terminology scope (so a result is never served for another server). Bounded by
 * {@code FV_TX_CACHE_SIZE} entries and {@code FV_TX_CACHE_TTL_MS}. A "not checked" answer is never stored.
 */
public final class TerminologyCodeCache {
    /** Heap one entry may hold (spec 007 research R15). */
    public static final long ENTRY_BYTES = 1024;

    public enum Op { VALIDATE_CODE, VALIDATE_CODE_IN_VS, LOOKUP_CODE }

    public static final String REASON_DEGRADED = "degraded";
    public static final String REASON_SNAPSHOT = "snapshot";
    public static final String REASON_ALLOWLIST = "allowlist";

    /** Hit, miss and invalidation counts (metrics, spec 010 FR-025); must be quick and never throw. */
    public interface Listener {
        Listener NONE = new Listener() { };

        default void hit(SupportedFhirVersion version) { }

        default void miss(SupportedFhirVersion version) { }

        default void invalidated(String reason, long entries) { }
    }

    /** One cache key (data-model §6). */
    public record Key(long snapshotId, String scopeId, Op op, SupportedFhirVersion fhirVersion, String system,
                      String code, String display, String valueSetKey, String optionsKey) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(fhirVersion, "fhirVersion");
        }
    }

    /** A stored "no answer" (HAPI caches empty results too). */
    private static final Object EMPTY = new Object();

    private final Cache<Key, Object> cache;
    private final int maximumSize;
    private volatile Listener listener = Listener.NONE;

    public TerminologyCodeCache(int maximumSize, Duration ttl) {
        this(maximumSize, ttl, Ticker.systemTicker());
    }

    public TerminologyCodeCache(int maximumSize, Duration ttl, Ticker ticker) {
        this.maximumSize = maximumSize;
        this.cache = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).ticker(ticker).build();
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    public int maximumSize() {
        return maximumSize;
    }

    /** A cached answer. */
    public record Hit<T>(T value) {
    }

    /** The cached answer for {@code key} ({@code value} may be null: a cached empty answer), or {@code null} on a miss. */
    @SuppressWarnings("unchecked")
    public <T> Hit<T> get(Key key) {
        Object v = cache.getIfPresent(key);
        if (v == null) {
            listener.miss(key.fhirVersion());
            return null;
        }
        listener.hit(key.fhirVersion());
        return new Hit<>(v == EMPTY ? null : (T) v);
    }

    /** Stores {@code value} ({@code null} = empty answer), unless it is a "not checked" answer (spec 010 FR-012). */
    public void put(Key key, Object value) {
        if (notChecked(value)) {
            return;
        }
        cache.put(key, value == null ? EMPTY : value);
    }

    /** Whether {@code value} is the degraded answer of spec 007 research R7. */
    public static boolean notChecked(Object value) {
        return value instanceof IValidationSupport.CodeValidationResult r
                && TerminologyClients.NOT_CHECKED_SOURCE.equals(r.getSourceDetails());
    }

    /** Drops every entry of {@code scopeId}, across all snapshots (after a degraded request, FR-013). */
    public void invalidateScope(String scopeId) {
        remove(k -> k.scopeId().equals(scopeId), REASON_DEGRADED);
    }

    /** Drops every entry of a snapshot that was replaced or dropped. */
    public void invalidateSnapshot(long snapshotId) {
        remove(k -> k.snapshotId() == snapshotId, REASON_SNAPSHOT);
    }

    /** Drops every {@code ecosystem} entry of {@code version} after its allowlist changed (routing may differ now). */
    public void invalidateEcosystem(SupportedFhirVersion version) {
        remove(k -> k.fhirVersion() == version && k.scopeId().startsWith("ecosystem:"), REASON_ALLOWLIST);
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }

    /** Test hook: runs pending maintenance (evictions) now. */
    public void cleanUp() {
        cache.cleanUp();
    }

    private void remove(Predicate<Key> which, String reason) {
        long[] removed = {0};
        cache.asMap().keySet().removeIf(k -> {
            boolean match = which.test(k);
            if (match) {
                removed[0]++;
            }
            return match;
        });
        if (removed[0] > 0) {
            listener.invalidated(reason, removed[0]);
        }
    }
}
