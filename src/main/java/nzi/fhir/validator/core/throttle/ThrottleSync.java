package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Shares this instance's request counts with the others (spec 014, research R4, R5). One {@link #tick()} per
 * {@code FV_THROTTLE_SYNC_INTERVAL_MS}; a tick never overlaps the previous one (it is skipped and counted instead):
 * <ol>
 *     <li><b>confirm</b>: the admissions flushed by the previous successful tick are visible to every live peer by now
 *     (each peer reads once per interval), so they no longer count as unconfirmed;</li>
 *     <li><b>flush</b>: this instance's changed per-second counts (absolute, so a repeat is harmless);</li>
 *     <li><b>heartbeat</b>: this instance is alive; how many others are;</li>
 *     <li><b>read</b>: the others' totals of the last 5 seconds, which replace the remote buckets.</li>
 * </ol>
 * When a step fails, or no read has succeeded for 3 intervals, the instance is in <b>local mode</b> (FR-019): peers
 * count as 0, so every cap is enforced on this instance's own counts, and nothing is refused because sharing failed.
 * The first fully successful tick ends it. All I/O goes through {@link CountStore}; the request path never waits for it.
 */
public final class ThrottleSync {
    private static final Logger logger = LogManager.getLogger(ThrottleSync.class);
    /** Seconds read on every tick (older buckets are final and already held). */
    static final int READ_SECONDS = 5;
    /** Counts older than this many seconds are deleted. */
    static final int KEEP_SECONDS = 120;
    static final long CLEANUP_INTERVAL_MS = 30_000;

    /** Metrics hooks (ServiceMetrics.bindThrottle). */
    public interface Listener {
        void failure(String step);

        void skipped();

        void tick(long nanos);

        Listener NONE = new Listener() {
            @Override
            public void failure(String step) {
            }

            @Override
            public void skipped() {
            }

            @Override
            public void tick(long nanos) {
            }
        };
    }

    private final Throttle throttle;
    private final CountStore store;
    private final long intervalMs;
    private final LongSupplier clock;
    private final UUID instanceId = UUID.randomUUID();
    private final String instanceName;
    private volatile Listener listener = Listener.NONE;

    private boolean running;
    /** The generation the previous tick flushed, when that flush succeeded; confirmed on the next tick. */
    private long flushedGeneration = Long.MIN_VALUE;
    private long lastReadOkMs = Long.MIN_VALUE;
    private long lastCleanupMs = Long.MIN_VALUE;
    private volatile boolean localMode;
    private long localSinceMs;
    private int livePeers;

    public ThrottleSync(Throttle throttle, CountStore store, long intervalMs, LongSupplier clock, String instanceName) {
        this.throttle = throttle;
        this.store = store;
        this.intervalMs = intervalMs;
        this.clock = clock;
        this.instanceName = instanceName;
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public boolean localMode() {
        return localMode;
    }

    /**
     * At start: a heartbeat and a read of the last 60 seconds, so a new instance doesn't begin with empty windows. A
     * failure starts the instance in local mode; it never fails the start.
     */
    public Future<Void> start() {
        long now = clock.getAsLong();
        long nowSec = Math.floorDiv(now, 1000);
        return store.heartbeat(instanceId, instanceName)
                .compose(peers -> store.read(instanceId, nowSec - (Window.SLOTS - 1)).map(rows -> {
                    apply(rows, nowSec);
                    livePeers = peers;
                    throttle.peers(peers);
                    lastReadOkMs = now;
                    return (Void) null;
                }))
                .recover(err -> {
                    enterLocalMode("start", err, now);
                    return Future.succeededFuture();
                });
    }

    /** One sync round (see the class comment). Completes when it has finished; never fails. */
    public Future<Void> tick() {
        if (running) {
            // A hung query (e.g. the database stopped answering) never completes its tick: local mode must still begin.
            listener.skipped();
            staleReadCheck(clock.getAsLong());
            return Future.succeededFuture();
        }
        running = true;
        long started = System.nanoTime();
        long now = clock.getAsLong();
        long nowSec = Math.floorDiv(now, 1000);
        if (flushedGeneration != Long.MIN_VALUE) {
            throttle.confirmThrough(flushedGeneration);
        }
        long closing = throttle.nextGeneration();
        List<CountStore.CountRow> rows = new ArrayList<>();
        List<Window> flushed = new ArrayList<>();
        throttle.forEachWindow((key, w) -> {
            for (long[] bucket : w.drainDirty(nowSec)) {
                rows.add(new CountStore.CountRow(key.scope(), key.id(), bucket[0], (int) bucket[1]));
                flushed.add(w);
            }
        });
        String[] step = {"flush"};
        return store.flush(instanceId, rows)
                .onFailure(err -> {
                    // Written again on the next tick.
                    for (int i = 0; i < rows.size(); i++) {
                        flushed.get(i).markDirty(rows.get(i).second(), nowSec);
                    }
                })
                .compose(v -> {
                    flushedGeneration = closing;
                    step[0] = "heartbeat";
                    return store.heartbeat(instanceId, instanceName);
                })
                .compose(peers -> {
                    livePeers = peers;
                    step[0] = "read";
                    return store.read(instanceId, nowSec - READ_SECONDS);
                })
                .map(read -> {
                    apply(read, nowSec);
                    lastReadOkMs = now;
                    throttle.peers(livePeers);
                    if (localMode) {
                        localMode = false;
                        // Measured now: a tick that hung while the database didn't answer started before local mode began.
                        logger.info("throttle: request counts shared again after {} s",
                                Math.max(0, clock.getAsLong() - localSinceMs) / 1000);
                    }
                    return (Void) null;
                })
                .recover(err -> {
                    // A failed flush leaves flushedGeneration at the previous (already confirmed) generation; its rows
                    // were marked dirty again and are written, and confirmed, with the next successful flush.
                    listener.failure(step[0]);
                    enterLocalMode(step[0], err, now);
                    return Future.succeededFuture();
                })
                .compose(v -> cleanupIfDue(now, nowSec))
                .onComplete(ar -> {
                    staleReadCheck(now);
                    listener.tick(System.nanoTime() - started);
                    running = false;
                });
    }

    private Future<Void> cleanupIfDue(long now, long nowSec) {
        if (lastCleanupMs != Long.MIN_VALUE && now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return Future.succeededFuture();
        }
        lastCleanupMs = now;
        return store.cleanup(nowSec - KEEP_SECONDS).recover(err -> {
            listener.failure("cleanup");
            return Future.succeededFuture();
        });
    }

    /** Research R5: no good read for 3 intervals is local mode too (e.g. a read that hangs). */
    private void staleReadCheck(long now) {
        if (!localMode && lastReadOkMs != Long.MIN_VALUE && now - lastReadOkMs > 3 * intervalMs) {
            enterLocalMode("read", new IllegalStateException("no successful read for " + (now - lastReadOkMs) + " ms"), now);
        }
    }

    private void enterLocalMode(String step, Throwable err, long now) {
        throttle.peers(0);
        if (!localMode) {
            localMode = true;
            localSinceMs = now;
            logger.warn("throttle: cannot share request counts ({}: {}); enforcing caps on this instance's counts only",
                    step, reason(err));
        }
    }

    private static String reason(Throwable err) {
        if (err instanceof io.vertx.pgclient.PgException pg) {
            return pg.getSqlState();
        }
        String m = err.getMessage();
        return m == null ? err.getClass().getSimpleName() : m;
    }

    private void apply(List<CountStore.CountRow> rows, long nowSec) {
        for (CountStore.CountRow r : rows) {
            throttle.window(r.scope(), r.subject()).setRemote(r.second(), r.count(), nowSec);
        }
    }

    /** Graceful stop: removes this instance's heartbeat, so peers stop reserving room for it at once. */
    public Future<Void> stop() {
        return store.remove(instanceId).recover(err -> Future.succeededFuture());
    }
}
