package nzi.fhir.validator.core.npm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The parsed package definitions shared by the validator contexts of one instance (spec 010 FR-017 to FR-019,
 * research R7, data-model §8).
 * <ul>
 *     <li>An entry lives while any context references it: the cache holds it weakly, and each context's
 *     {@code CustomNpmPackageValidationSupport} holds it strongly. After the last context using it is replaced or
 *     dropped it is collected (FR-019); nobody counts references.</li>
 *     <li>At most one parse of a key runs at a time (FR-018): a build that misses takes the key's lock and looks again;
 *     concurrent builds for the same key wait on the lock and reuse the result. A parse that fails stores nothing, so
 *     the next waiter tries it in turn. No future is created or joined (constitution I).</li>
 * </ul>
 * {@link #getOrParse} blocks while another build parses the same package: call it on a package worker only, never on
 * an event loop.
 */
public final class SharedPackageDefinitions {
    /** Lookup outcomes, for metrics (spec 010 FR-025); must be quick and never throw. */
    public interface Listener {
        Listener NONE = new Listener() { };

        default void hit(SupportedFhirVersion version) { }

        default void miss(SupportedFhirVersion version) { }

        default void failure(SupportedFhirVersion version) { }
    }

    private final Cache<SharedPackage.Key, SharedPackage> entries = Caffeine.newBuilder().weakValues().build();
    /** One lock per key while a parse may happen; removed after a successful put (never after a failure). */
    private final Map<SharedPackage.Key, Object> locks = new ConcurrentHashMap<>();
    private volatile Listener listener = Listener.NONE;

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /**
     * The shared definitions of {@code key}, parsing them with {@code parser} when no context holds them. Must run on
     * a package worker (it may wait for another build's parse).
     *
     * @throws RuntimeException whatever {@code parser} throws; nothing is stored then
     */
    public SharedPackage getOrParse(SharedPackage.Key key, Supplier<SharedPackage> parser) {
        SharedPackage hit = entries.getIfPresent(key);
        if (hit != null) {
            listener.hit(key.fhirVersion());
            return hit;
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            hit = entries.getIfPresent(key);
            if (hit != null) {
                listener.hit(key.fhirVersion());
                return hit;
            }
            SharedPackage parsed;
            try {
                parsed = parser.get();
            } catch (RuntimeException | Error e) {
                listener.failure(key.fhirVersion());
                throw e;
            }
            entries.put(key, parsed);
            locks.remove(key, lock);
            listener.miss(key.fhirVersion());
            return parsed;
        }
    }

    /** Whether {@code key}'s definitions are held by some context now (a build of it would not parse it). */
    public boolean contains(SharedPackage.Key key) {
        return entries.getIfPresent(key) != null;
    }

    /** Entries of {@code version} still held by some context (collected ones may linger until maintenance runs). */
    public long entries(SupportedFhirVersion version) {
        entries.cleanUp();
        return entries.asMap().keySet().stream().filter(k -> k.fhirVersion() == version).count();
    }

    /** Unpacked bytes of {@code version}'s entries. */
    public long expandedBytes(SupportedFhirVersion version) {
        entries.cleanUp();
        return entries.asMap().values().stream().filter(p -> p.key().fhirVersion() == version)
                .mapToLong(SharedPackage::expandedBytes).sum();
    }
}
