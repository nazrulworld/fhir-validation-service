package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Holds this instance's current {@link PackageCatalogView} and refreshes it from PostgreSQL (spec 004, research R4).
 * A derived, rebuildable cache (Principle II): kept current by {@code PACKAGE} notifications, the revision poll, local
 * stores, and lookup misses.
 * <p>
 * Concurrent refreshes share one in-flight load. A lookup miss may force a refresh at most once every
 * {@value #MISS_REFRESH_MIN_INTERVAL_MS} ms, so a burst of unknown {@code ig} values costs at most one metadata query
 * per second.
 */
public class PackageCatalog {
    public static final long MISS_REFRESH_MIN_INTERVAL_MS = 1_000;

    private final Supplier<Future<PackageCatalogView>> loader;
    private final LongSupplier clock;
    private volatile PackageCatalogView current = PackageCatalogView.EMPTY;
    private Future<PackageCatalogView> inFlight;
    private long lastRefreshStartedAt = Long.MIN_VALUE / 2;
    /** Set when this instance knows the stored packages changed since the last refresh started. */
    private boolean stale;

    public PackageCatalog(Supplier<Future<PackageCatalogView>> loader, LongSupplier clock) {
        this.loader = loader;
        this.clock = clock;
    }

    public static PackageCatalog of(Pool pool) {
        return new PackageCatalog(() -> PackageCatalogView.load(pool), System::currentTimeMillis);
    }

    public PackageCatalogView current() {
        return current;
    }

    /** Loads the latest metadata; callers arriving while a load runs share it. Never replaces a view with an older one. */
    public synchronized Future<PackageCatalogView> refresh() {
        if (inFlight != null) {
            return inFlight;
        }
        lastRefreshStartedAt = clock.getAsLong();
        stale = false;
        Promise<PackageCatalogView> promise = Promise.promise();
        inFlight = promise.future();
        Future<PackageCatalogView> load;
        try {
            load = loader.get();
        } catch (RuntimeException e) {
            load = Future.failedFuture(e);
        }
        load.onComplete(ar -> {
            synchronized (this) {
                inFlight = null;
                if (ar.succeeded() && ar.result().revision() >= current.revision()) {
                    current = ar.result();
                }
            }
            if (ar.succeeded()) {
                promise.complete(current);
            } else {
                promise.fail(ar.cause());
            }
        });
        return promise.future();
    }

    /**
     * Records that a package was stored or removed on this instance: the next lookup miss reloads the view even within
     * the rate-limit window, so a package stored here is never reported as missing here.
     */
    public synchronized void markStale() {
        stale = true;
    }

    /**
     * For a lookup miss: refreshes unless the last refresh started less than {@value #MISS_REFRESH_MIN_INTERVAL_MS} ms
     * ago, in which case it completes with the current view.
     */
    public synchronized Future<PackageCatalogView> refreshOnMiss() {
        if (stale) {
            // A package was stored here after the running load (if any) started: wait for it, then load again.
            return inFlight != null ? inFlight.transform(ar -> refreshOnMiss()) : refresh();
        }
        if (inFlight != null) {
            return inFlight;
        }
        if (clock.getAsLong() - lastRefreshStartedAt < MISS_REFRESH_MIN_INTERVAL_MS) {
            return Future.succeededFuture(current);
        }
        return refresh();
    }
}
