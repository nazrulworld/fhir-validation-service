package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The validator contexts of this instance (spec 004, research R2, data-model §2.5): one per {@link SnapshotKey}
 * (FHIR version plus the one IG package it validates against). A derived, rebuildable cache: PostgreSQL holds the
 * configuration and the packages, this class holds the snapshots built from them (Principle II).
 * <ul>
 *     <li><b>Base</b> contexts: one per enabled version, built from no package (the core specification only, R1).
 *     Never removed.</li>
 *     <li><b>Pinned</b> contexts: one per IG included for a version, driven by {@link #applyPinned}. Never evicted.
 *     A failed build makes the context STALE (503) and is retried with backoff.</li>
 *     <li><b>On-demand</b> contexts: built at the first request that names a stored package ({@link #snapshot}),
 *     single-flight per key, bounded by {@code maxOnDemand} and by memory (least recently used first). A failed build
 *     removes the slot; the next request tries again (FR-017).</li>
 * </ul>
 * A request waits for an on-demand build at most {@code buildWaitMs}, then gets 503 while the build continues
 * (FR-012). Starting an on-demand build takes one slot of the administrative admission controller (FR-014).
 * <p>
 * All state is guarded by one lock; critical sections are short (builds and promise completion happen outside it).
 */
public class ValidatorRegistry {
    private static final Logger logger = LogManager.getLogger(ValidatorRegistry.class);
    private static final long INITIAL_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 60_000;
    static final int DEFAULT_MAX_ON_DEMAND = 4;
    static final long DEFAULT_BUILD_WAIT_MS = 10_000;

    public enum Status { ABSENT, BUILDING, ACTIVE, STALE }

    public enum Kind { BASE, PINNED, ON_DEMAND }

    public static final String BUILD_SUCCESS = "success";
    public static final String BUILD_FAILURE = "failure";
    public static final String BUILD_TIMEOUT_WAIT = "timeout_wait";
    public static final String BUILD_REFUSED_CAPACITY = "refused_capacity";
    public static final String BUILD_REFUSED_ADMISSION = "refused_admission";
    public static final String EVICT_LRU = "lru";
    public static final String EVICT_MEMORY = "memory";
    public static final String EVICT_INVALIDATED = "invalidated";
    public static final String EVICT_EXCLUDED = "excluded";
    public static final String LOOKUP_HIT = "hit";
    public static final String LOOKUP_JOINED = "joined";
    public static final String LOOKUP_BUILT = "built";

    /**
     * Context lifecycle events, for metrics (spec 004 FR-033). May be called while the registry lock is held, so an
     * implementation must be quick and must not call back into the registry.
     */
    public interface Events {
        Events NONE = new Events() { };

        default void build(SnapshotKey key, Kind kind, String outcome) { }

        default void eviction(SnapshotKey key, String reason) { }

        default void lookup(SnapshotKey key, String result) { }
    }

    private static final class Slot {
        final SnapshotKey key;
        Kind kind;
        /** Volatile: read without the lock on the hot path for base and pinned contexts (written under it). */
        volatile ValidatorSnapshot snapshot;
        ResolvedContext activeResolved;
        volatile Status status = Status.ABSENT;
        String reason;
        long appliedRevision;
        long wantedRevision;
        ResolvedContext wanted = ResolvedContext.EMPTY;
        boolean building;
        boolean dirty;
        boolean admissionHeld;
        long retryDelayMs = INITIAL_RETRY_MS;
        long retryTimer = -1;
        volatile long lastUsed;
        final List<Promise<Void>> applyWaiters = new ArrayList<>();
        final List<Promise<ValidatorSnapshot>> requestWaiters = new ArrayList<>();

        Slot(SnapshotKey key, Kind kind) {
            this.key = key;
            this.kind = kind;
        }
    }

    private static final class VersionState {
        long appliedRevision;
        long latestSeenRevision;
        long wantedRevision;
    }

    private final Object lock = new Object();
    private final Vertx vertx;
    private final SnapshotBuilder builder;
    private final int maxOnDemand;
    private final long buildWaitMs;
    private final AdmissionController buildAdmission;
    /**
     * Base and pinned slots. Concurrent so validation can read them without the registry lock (SC-007); the map and
     * the slots' fields are still only written under the lock.
     */
    private final Map<SnapshotKey, Slot> pinned = new java.util.concurrent.ConcurrentHashMap<>();
    /** Access-ordered: iteration starts at the least recently used slot. */
    private final LinkedHashMap<SnapshotKey, Slot> onDemand = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<SupportedFhirVersion, VersionState> versions = new EnumMap<>(SupportedFhirVersion.class);
    private volatile Supplier<PackageCatalogView> catalog = () -> PackageCatalogView.EMPTY;
    private volatile Consumer<SupportedFhirVersion> buildFailureListener = v -> { };
    private volatile Events events = Events.NONE;

    public ValidatorRegistry(Vertx vertx, SnapshotBuilder builder) {
        this(vertx, builder, DEFAULT_MAX_ON_DEMAND, DEFAULT_BUILD_WAIT_MS, null);
    }

    /**
     * @param maxOnDemand     most on-demand contexts kept ({@code FV_IG_MAX_ON_DEMAND})
     * @param buildWaitMs     how long a request waits for an on-demand build ({@code FV_IG_BUILD_WAIT_MS})
     * @param buildAdmission  admission for on-demand builds (the administrative controller), or {@code null}
     */
    public ValidatorRegistry(Vertx vertx, SnapshotBuilder builder, int maxOnDemand, long buildWaitMs,
                             AdmissionController buildAdmission) {
        this.vertx = vertx;
        this.builder = builder;
        this.maxOnDemand = maxOnDemand;
        this.buildWaitMs = buildWaitMs;
        this.buildAdmission = buildAdmission;
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            versions.put(v, new VersionState());
            SnapshotKey base = SnapshotKey.base(v);
            pinned.put(base, new Slot(base, Kind.BASE));
        }
    }

    public void setBuildFailureListener(Consumer<SupportedFhirVersion> listener) {
        this.buildFailureListener = listener;
    }

    /** Source of the current package metadata, used to resolve on-demand contexts. */
    public void setCatalog(Supplier<PackageCatalogView> catalog) {
        this.catalog = catalog;
    }

    public void setEvents(Events events) {
        this.events = events == null ? Events.NONE : events;
    }

    private VersionState versionState(SupportedFhirVersion version) {
        VersionState state = versions.get(version);
        if (state == null) {
            throw ProblemException.unsupportedVersion(version.name());
        }
        return state;
    }

    // --- serving -----------------------------------------------------------------------------------------------

    /**
     * The snapshot of a base, pinned or already built on-demand context, without building anything. While a newer
     * configuration is being built, the previous snapshot keeps serving; a STALE or absent context answers 503.
     */
    public ValidatorSnapshot current(SnapshotKey key) {
        synchronized (lock) {
            versionState(key.fhirVersion());
            Slot slot = pinned.get(key);
            if (slot == null) {
                slot = onDemand.get(key);
            }
            if (slot == null) {
                throw ProblemException.notReady("The validator for " + key + " is not ready yet");
            }
            return serveable(slot);
        }
    }

    /** Shorthand for the base context of {@code version}. */
    public ValidatorSnapshot current(SupportedFhirVersion version) {
        return current(SnapshotKey.base(version));
    }

    private static ValidatorSnapshot serveable(Slot slot) {
        if (slot.status == Status.STALE) {
            throw ProblemException.notReady("The validator for " + slot.key + " is out of date and being rebuilt");
        }
        ValidatorSnapshot snapshot = slot.snapshot;
        if (snapshot == null) {
            throw ProblemException.notReady("The validator for " + slot.key + " is not ready yet");
        }
        return snapshot;
    }

    /**
     * The snapshot to validate with. Base and pinned contexts answer from memory (or 503). Any other key is an
     * on-demand context: an existing one is reused, a running build is joined, otherwise a build starts from the
     * stored packages (never from a registry). The caller resolved {@code key} against the catalog first.
     */
    public Future<ValidatorSnapshot> snapshot(SnapshotKey key) {
        // Hot path: base and pinned contexts are served from volatile state, without the registry lock.
        Slot fast = pinned.get(key);
        if (fast != null) {
            try {
                ValidatorSnapshot serving = serveable(fast);
                fast.lastUsed = System.currentTimeMillis();
                emitLookup(key, LOOKUP_HIT);
                return Future.succeededFuture(serving);
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
        }
        Slot toBuild = null;
        Future<ValidatorSnapshot> result;
        String lookup;
        synchronized (lock) {
            try {
                versionState(key.fhirVersion());
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
            Slot slot = pinned.get(key);
            if (slot != null || key.isBase()) {
                try {
                    if (slot == null) {
                        throw ProblemException.notReady("The validator for " + key + " is not ready yet");
                    }
                    slot.lastUsed = System.currentTimeMillis();
                    result = Future.succeededFuture(serveable(slot));
                    lookup = LOOKUP_HIT;
                } catch (ProblemException e) {
                    return Future.failedFuture(e);
                }
            } else if ((slot = onDemand.get(key)) != null) {
                if (slot.status == Status.ACTIVE && slot.snapshot != null) {
                    slot.lastUsed = System.currentTimeMillis();
                    result = Future.succeededFuture(slot.snapshot);
                    lookup = LOOKUP_HIT;
                } else {
                    result = waitFor(slot);
                    lookup = LOOKUP_JOINED;
                }
            } else {
                PackageCatalogView view = catalog.get();
                ResolvedContext wanted = ResolvedContext.of(key.igPackage(), view);
                if (!wanted.missing().isEmpty()) {
                    // A dependency is not stored: building would silently validate against less (FR-017).
                    emitBuild(key, Kind.ON_DEMAND, BUILD_FAILURE);
                    return Future.failedFuture(ProblemException.dependencyNotFound(wanted.missing().get(0)));
                }
                if (buildAdmission != null && !buildAdmission.tryAcquire()) {
                    ProblemException refused = buildAdmission.overloaded();
                    emitBuild(key, Kind.ON_DEMAND, BUILD_REFUSED_ADMISSION);
                    return Future.failedFuture(refused);
                }
                List<SnapshotKey> evicted = new ArrayList<>();
                if (!makeRoomLocked(maxOnDemand - 1, evicted)) {
                    if (buildAdmission != null) {
                        buildAdmission.release();
                    }
                    emitBuild(key, Kind.ON_DEMAND, BUILD_REFUSED_ADMISSION);
                    return Future.failedFuture(new ProblemException(503, "overloaded",
                            "Too many IG validator contexts are being built; retry shortly").withHeader("Retry-After", "1"));
                }
                evicted.forEach(k -> emitEviction(k, EVICT_LRU));
                slot = new Slot(key, Kind.ON_DEMAND);
                slot.wanted = wanted;
                slot.wantedRevision = view.revision();
                slot.building = true;
                slot.status = Status.BUILDING;
                slot.admissionHeld = buildAdmission != null;
                slot.lastUsed = System.currentTimeMillis();
                onDemand.put(key, slot);
                result = waitFor(slot);
                lookup = LOOKUP_BUILT;
                toBuild = slot;
            }
        }
        emitLookup(key, lookup);
        if (toBuild != null) {
            build(toBuild);
        }
        return result;
    }

    /** Registers a request waiting for {@code slot}'s build, failing it with 503 after {@code buildWaitMs}. */
    private Future<ValidatorSnapshot> waitFor(Slot slot) {
        Promise<ValidatorSnapshot> promise = Promise.promise();
        slot.requestWaiters.add(promise);
        if (vertx != null) {
            SnapshotKey key = slot.key;
            vertx.setTimer(buildWaitMs, id -> {
                if (promise.tryFail(ProblemException.notReady("The validator for " + key
                        + " is still being built; retry shortly"))) {
                    synchronized (lock) {
                        slot.requestWaiters.remove(promise);
                    }
                    emitBuild(key, Kind.ON_DEMAND, BUILD_TIMEOUT_WAIT);
                }
            });
        }
        return promise.future();
    }

    /**
     * Evicts least recently used on-demand slots that are not building until at most {@code target} remain.
     *
     * @return false if that is impossible because the rest are all building
     */
    private boolean makeRoomLocked(int target, List<SnapshotKey> evicted) {
        while (onDemand.size() > Math.max(0, target)) {
            Slot victim = null;
            for (Slot s : onDemand.values()) {
                if (!s.building) {
                    victim = s;
                    break;
                }
            }
            if (victim == null) {
                return false;
            }
            onDemand.remove(victim.key);
            evicted.add(victim.key);
        }
        return true;
    }

    /**
     * Frees memory for a build by dropping the least recently used on-demand context that is not building and is not
     * {@code except} (research R7). Requests already validating with it keep their reference and finish.
     */
    public boolean evictOne(SnapshotKey except) {
        SnapshotKey victim = null;
        synchronized (lock) {
            for (Slot s : onDemand.values()) {
                if (!s.building && !s.key.equals(except)) {
                    victim = s.key;
                    break;
                }
            }
            if (victim != null) {
                onDemand.remove(victim);
            }
        }
        if (victim == null) {
            return false;
        }
        logger.info("Evicted on-demand validator context {} to free memory", victim);
        emitEviction(victim, EVICT_MEMORY);
        return true;
    }

    /** The evictor for builds that are not on behalf of a particular context (e.g. include admission). */
    public HeapBudget.Evictor evictor() {
        return () -> evictOne(null);
    }

    // --- pinned configuration ---------------------------------------------------------------------------------

    /**
     * Applies configuration revision {@code revision} of {@code version}: the base context plus one pinned context per
     * entry of {@code pinnedContexts} ({@code name#version} → its resolved packages). New keys are built, keys whose
     * packages or contents changed are rebuilt (the old snapshot keeps serving meanwhile), unchanged keys are left
     * alone, and keys no longer included are demoted to on-demand (evictable, not counted for readiness). Completes
     * when every context of the revision is active; fails if a build fails (that context is then STALE and retries).
     */
    public Future<Void> applyPinned(SupportedFhirVersion version, long revision, Map<String, ResolvedContext> pinnedContexts) {
        List<Future<Void>> waits = new ArrayList<>();
        List<Slot> toBuild = new ArrayList<>();
        List<SnapshotKey> evicted = new ArrayList<>();
        synchronized (lock) {
            VersionState state;
            try {
                state = versionState(version);
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
            state.latestSeenRevision = Math.max(state.latestSeenRevision, revision);
            if (revision < state.wantedRevision) {
                // An older revision arrived late; a newer one is already wanted.
                return Future.succeededFuture();
            }
            state.wantedRevision = revision;
            Map<SnapshotKey, ResolvedContext> desired = new LinkedHashMap<>();
            desired.put(SnapshotKey.base(version), ResolvedContext.EMPTY);
            pinnedContexts.forEach((ig, ctx) -> desired.put(SnapshotKey.of(version, ig), ctx));

            // Excluded keys: demoted to on-demand, kept while memory and the bound allow (research R10). A context an
            // on-demand request could not get (a dependency is not stored, FR-017) is dropped instead, so the excluded
            // IG behaves exactly like any other stored package from now on (US2-6).
            PackageCatalogView view = catalog.get();
            for (Iterator<Map.Entry<SnapshotKey, Slot>> it = pinned.entrySet().iterator(); it.hasNext(); ) {
                Slot s = it.next().getValue();
                if (s.kind == Kind.PINNED && s.key.fhirVersion() == version && !desired.containsKey(s.key)) {
                    it.remove();
                    cancelRetryLocked(s);
                    s.kind = Kind.ON_DEMAND;
                    boolean missingDependency = !ResolvedContext.of(s.key.igPackage(), view).missing().isEmpty();
                    if ((s.snapshot != null || s.building) && !missingDependency) {
                        onDemand.put(s.key, s);
                        logger.info("Validator context {} is no longer included; kept on demand", s.key);
                    } else {
                        evicted.add(s.key);
                        logger.info("Validator context {} is no longer included; dropped", s.key);
                    }
                }
            }
            makeRoomLocked(maxOnDemand, evicted);

            for (Map.Entry<SnapshotKey, ResolvedContext> e : desired.entrySet()) {
                SnapshotKey key = e.getKey();
                ResolvedContext ctx = e.getValue();
                Slot s = pinned.get(key);
                if (s == null) {
                    s = onDemand.remove(key);
                    if (s != null) {
                        s.kind = Kind.PINNED;
                        logger.info("On-demand validator context {} is now included (pinned)", key);
                    } else {
                        s = new Slot(key, key.isBase() ? Kind.BASE : Kind.PINNED);
                    }
                    pinned.put(key, s);
                }
                s.wantedRevision = Math.max(s.wantedRevision, revision);
                if (!s.building && s.snapshot != null && ctx.sameAs(s.activeResolved)) {
                    s.wanted = ctx;
                    s.appliedRevision = Math.max(s.appliedRevision, revision);
                    s.status = Status.ACTIVE;
                    s.reason = null;
                    cancelRetryLocked(s);
                    continue;
                }
                Promise<Void> promise = Promise.promise();
                s.applyWaiters.add(promise);
                waits.add(promise.future());
                if (s.building) {
                    if (!ctx.sameAs(s.wanted)) {
                        s.wanted = ctx;
                        s.dirty = true;
                    }
                } else {
                    s.wanted = ctx;
                    s.building = true;
                    if (s.status != Status.STALE) {
                        s.status = s.snapshot == null ? Status.BUILDING : Status.ACTIVE;
                    }
                    cancelRetryLocked(s);
                    toBuild.add(s);
                }
            }
        }
        evicted.forEach(k -> emitEviction(k, EVICT_EXCLUDED));
        toBuild.forEach(this::build);
        return Future.join(waits).<Void>mapEmpty().onSuccess(v -> {
            synchronized (lock) {
                VersionState state = versions.get(version);
                state.appliedRevision = Math.max(state.appliedRevision, revision);
            }
        });
    }

    /**
     * The package catalog changed (spec 004 FR-025, research R6). Pinned contexts built from a package whose stored
     * content was replaced stop serving (STALE) until rebuilt by the next {@link #applyPinned}; on-demand contexts
     * that contain a replaced package, or whose resolution would now differ, are removed (or, while building,
     * redirected to the new resolution).
     */
    public void onCatalogChanged(PackageCatalogView view) {
        List<SnapshotKey> removed = new ArrayList<>();
        synchronized (lock) {
            for (Slot s : pinned.values()) {
                if (s.kind == Kind.PINNED && s.snapshot != null && s.activeResolved != null) {
                    List<String> replaced = s.activeResolved.replacedIn(view);
                    if (!replaced.isEmpty() && s.status != Status.STALE) {
                        s.status = Status.STALE;
                        s.reason = "package " + replaced.get(0) + " was replaced";
                        logger.warn("Validator context {} is out of date: {}", s.key, s.reason);
                    }
                }
            }
            for (Iterator<Slot> it = onDemand.values().iterator(); it.hasNext(); ) {
                Slot s = it.next();
                ResolvedContext now = ResolvedContext.of(s.key.igPackage(), view);
                if (s.building) {
                    if (!now.sameAs(s.wanted)) {
                        s.wanted = now;
                        s.wantedRevision = view.revision();
                        s.dirty = true;
                    }
                } else if (!now.sameAs(s.activeResolved)) {
                    it.remove();
                    removed.add(s.key);
                }
            }
        }
        removed.forEach(k -> {
            logger.info("On-demand validator context {} dropped: its packages changed", k);
            emitEviction(k, EVICT_INVALIDATED);
        });
    }

    // --- building ---------------------------------------------------------------------------------------------

    private void build(Slot slot) {
        long revision;
        ResolvedContext resolved;
        synchronized (lock) {
            revision = slot.wantedRevision;
            resolved = slot.wanted;
            slot.dirty = false;
        }
        Future<ValidatorSnapshot> future;
        try {
            future = builder.build(slot.key, revision, resolved, () -> evictOne(slot.key));
        } catch (RuntimeException e) {
            future = Future.failedFuture(e);
        }
        future.onComplete(ar -> {
            List<Promise<Void>> applyDone = List.of();
            List<Promise<ValidatorSnapshot>> requestDone = List.of();
            boolean again;
            boolean releaseAdmission = false;
            boolean retry = false;
            Kind kind;
            ValidatorSnapshot built = null;
            synchronized (lock) {
                kind = slot.kind;
                if (ar.succeeded()) {
                    built = ar.result();
                    slot.snapshot = built;
                    slot.activeResolved = resolved;
                    slot.appliedRevision = Math.max(slot.appliedRevision,
                            resolved.sameAs(slot.wanted) ? slot.wantedRevision : revision);
                    slot.status = Status.ACTIVE;
                    slot.reason = null;
                    slot.retryDelayMs = INITIAL_RETRY_MS;
                    slot.lastUsed = System.currentTimeMillis();
                    again = slot.dirty || !resolved.sameAs(slot.wanted);
                } else if (kind == Kind.ON_DEMAND) {
                    if (onDemand.get(slot.key) == slot) {
                        onDemand.remove(slot.key);
                    }
                    again = false;
                } else {
                    slot.status = Status.STALE;
                    slot.reason = describe(ar.cause());
                    again = slot.dirty;
                    retry = !again;
                }
                if (!again) {
                    slot.building = false;
                    applyDone = new ArrayList<>(slot.applyWaiters);
                    requestDone = new ArrayList<>(slot.requestWaiters);
                    slot.applyWaiters.clear();
                    slot.requestWaiters.clear();
                    releaseAdmission = slot.admissionHeld;
                    slot.admissionHeld = false;
                }
            }
            if (ar.succeeded()) {
                emitBuild(slot.key, kind, BUILD_SUCCESS);
            } else {
                emitBuild(slot.key, kind, ar.cause() instanceof HeapBudget.InsufficientHeapException
                        ? BUILD_REFUSED_CAPACITY : BUILD_FAILURE);
                logger.error("Validator build for {} at revision {} failed", slot.key, revision, ar.cause());
                if (kind != Kind.ON_DEMAND) {
                    buildFailureListener.accept(slot.key.fhirVersion());
                }
            }
            if (again) {
                build(slot);
                return;
            }
            if (releaseAdmission && buildAdmission != null) {
                buildAdmission.release();
            }
            if (ar.succeeded()) {
                applyDone.forEach(Promise::tryComplete);
                ValidatorSnapshot snapshot = built;
                requestDone.forEach(p -> p.tryComplete(snapshot));
            } else {
                applyDone.forEach(p -> p.tryFail(ar.cause()));
                ProblemException problem = toProblem(slot.key, ar.cause());
                requestDone.forEach(p -> p.tryFail(problem));
                if (retry) {
                    scheduleRetry(slot);
                }
            }
        });
    }

    private void scheduleRetry(Slot slot) {
        long delay;
        synchronized (lock) {
            if (slot.retryTimer >= 0 || vertx == null || slot.kind == Kind.ON_DEMAND) {
                return;
            }
            delay = slot.retryDelayMs;
            slot.retryDelayMs = Math.min(slot.retryDelayMs * 2, MAX_RETRY_MS);
            slot.retryTimer = vertx.setTimer(delay, id -> {
                boolean start;
                synchronized (lock) {
                    slot.retryTimer = -1;
                    start = slot.kind != Kind.ON_DEMAND && pinned.get(slot.key) == slot && !slot.building
                            && slot.status == Status.STALE;
                    if (start) {
                        slot.building = true;
                    }
                }
                if (start) {
                    build(slot);
                }
            });
        }
        logger.info("Retrying validator build for {} in {} ms", slot.key, delay);
    }

    private void cancelRetryLocked(Slot slot) {
        if (slot.retryTimer >= 0 && vertx != null) {
            vertx.cancelTimer(slot.retryTimer);
        }
        slot.retryTimer = -1;
    }

    /** The client-facing problem for a failed on-demand build (curated text only; causes are logged). */
    static ProblemException toProblem(SnapshotKey key, Throwable cause) {
        if (cause instanceof ProblemException p) {
            return p;
        }
        if (cause instanceof SnapshotBuilder.MissingPackageException missing) {
            return ProblemException.dependencyNotFound(missing.idAndVersion());
        }
        if (cause instanceof HeapBudget.NeverFitsException never) {
            return new ProblemException(413, "package-too-large", "The validator for IG package " + key.igPackage()
                    + " cannot be built on this instance: it " + never.getMessage());
        }
        if (cause instanceof HeapBudget.InsufficientHeapException) {
            return ProblemException.insufficientCapacity();
        }
        return ProblemException.notReady("The validator for " + key + " could not be built; retry shortly");
    }

    /**
     * Curated reason shown in {@code /health} and readiness: names a missing package, otherwise generic. Exception
     * classes and messages go to the log only (constitution Principle IV).
     */
    private static String describe(Throwable t) {
        if (t instanceof SnapshotBuilder.MissingPackageException) {
            return t.getMessage();
        }
        if (t instanceof HeapBudget.InsufficientHeapException) {
            return "validator build " + t.getMessage();
        }
        return "validator build failed (see the instance log)";
    }

    // --- state for health, readiness and metrics ----------------------------------------------------------------

    public void noteSeen(SupportedFhirVersion version, long revision) {
        synchronized (lock) {
            VersionState state = versionState(version);
            state.latestSeenRevision = Math.max(state.latestSeenRevision, revision);
        }
    }

    /** The highest configuration revision of {@code version} fully applied on this instance. */
    public long appliedRevision(SupportedFhirVersion version) {
        synchronized (lock) {
            return versionState(version).appliedRevision;
        }
    }

    /**
     * @return empty when every base and pinned context can serve requests; on-demand contexts never count (FR-032)
     */
    public List<String> notReadyReasons() {
        List<String> reasons = new ArrayList<>();
        synchronized (lock) {
            pinned.values().stream()
                    .sorted((a, b) -> a.key.toString().compareTo(b.key.toString()))
                    .forEach(slot -> {
                        if (slot.status == Status.STALE) {
                            reasons.add(label(slot.key) + " validator is out of date: " + slot.reason);
                        } else if (slot.snapshot == null) {
                            reasons.add(label(slot.key) + " validator is " + slot.status.name().toLowerCase());
                        }
                    });
        }
        return reasons;
    }

    private static String label(SnapshotKey key) {
        return key.isBase() ? key.fhirVersion().name() : key.toString();
    }

    public boolean isReady() {
        return notReadyReasons().isEmpty();
    }

    /**
     * Per version: {@code {base: {...}, contexts: [{key, kind, state, ...}]}} (contracts/http-api.md §5).
     */
    public JsonObject details() {
        JsonObject details = new JsonObject();
        synchronized (lock) {
            for (SupportedFhirVersion version : versions.keySet()) {
                JsonArray contexts = new JsonArray();
                List<Slot> slots = new ArrayList<>();
                pinned.values().stream().filter(s -> s.key.fhirVersion() == version && !s.key.isBase()).forEach(slots::add);
                onDemand.values().stream().filter(s -> s.key.fhirVersion() == version).forEach(slots::add);
                slots.sort((a, b) -> a.key.igPackage().compareTo(b.key.igPackage()));
                slots.forEach(s -> contexts.add(describeSlot(s).put("key", s.key.igPackage())
                        .put("kind", s.kind == Kind.ON_DEMAND ? "on-demand" : "pinned")));
                VersionState state = versions.get(version);
                details.put(version.name(), new JsonObject()
                        .put("appliedRevision", state.appliedRevision)
                        .put("latestRevision", state.latestSeenRevision)
                        .put("base", describeSlot(pinned.get(SnapshotKey.base(version))))
                        .put("contexts", contexts));
            }
        }
        return details;
    }

    private static JsonObject describeSlot(Slot slot) {
        JsonObject d = new JsonObject()
                .put("state", slot.building && slot.status == Status.ACTIVE ? "ACTIVE (rebuilding)" : slot.status.name())
                .put("appliedRevision", slot.appliedRevision)
                .put("packages", slot.snapshot == null ? List.of() : slot.snapshot.resolvedPackages())
                .put("packageBytes", slot.snapshot == null ? 0 : slot.snapshot.packageArchiveBytes());
        if (slot.lastUsed > 0 && !slot.key.isBase()) {
            d.put("lastUsed", Instant.ofEpochMilli(slot.lastUsed).toString());
        }
        if (slot.reason != null) {
            d.put("reason", slot.reason);
        }
        return d;
    }

    /** Package count of the version's base and pinned contexts. */
    public long snapshotPackages(SupportedFhirVersion version) {
        synchronized (lock) {
            return pinned.values().stream().filter(s -> s.key.fhirVersion() == version && s.snapshot != null)
                    .mapToLong(s -> s.snapshot.resolvedPackages().size()).sum();
        }
    }

    /** Archive bytes of the version's base and pinned contexts. */
    public long snapshotPackageBytes(SupportedFhirVersion version) {
        synchronized (lock) {
            return pinned.values().stream().filter(s -> s.key.fhirVersion() == version && s.snapshot != null)
                    .mapToLong(s -> s.snapshot.packageArchiveBytes()).sum();
        }
    }

    /** Number of contexts of {@code version} of the given kind that hold a snapshot or are building. */
    public long contextCount(SupportedFhirVersion version, Kind kind) {
        synchronized (lock) {
            Map<SnapshotKey, Slot> source = kind == Kind.ON_DEMAND ? onDemand : pinned;
            return source.values().stream().filter(s -> s.key.fhirVersion() == version && s.kind == kind).count();
        }
    }

    /** Status of the version as a whole: STALE or not ready if its base or any pinned context is. */
    public Status status(SupportedFhirVersion version) {
        synchronized (lock) {
            versionState(version);
            Status worst = Status.ACTIVE;
            for (Slot s : pinned.values()) {
                if (s.key.fhirVersion() != version) {
                    continue;
                }
                if (s.status == Status.STALE) {
                    return Status.STALE;
                }
                if (s.snapshot == null) {
                    worst = s.status == Status.ACTIVE ? Status.BUILDING : s.status;
                }
            }
            return worst;
        }
    }

    public Status status(SnapshotKey key) {
        synchronized (lock) {
            Slot s = pinned.get(key);
            if (s == null) {
                s = onDemand.get(key);
            }
            return s == null ? Status.ABSENT : s.status;
        }
    }

    /** Kind of the context {@code key} on this instance, or {@code null} when there is none. */
    public Kind kind(SnapshotKey key) {
        synchronized (lock) {
            Slot s = pinned.get(key);
            if (s == null) {
                s = onDemand.get(key);
            }
            return s == null ? null : s.kind;
        }
    }

    private void emitBuild(SnapshotKey key, Kind kind, String outcome) {
        try {
            events.build(key, kind, outcome);
        } catch (RuntimeException e) {
            logger.debug("Context event listener failed: {}", e.toString());
        }
    }

    private void emitEviction(SnapshotKey key, String reason) {
        try {
            events.eviction(key, reason);
        } catch (RuntimeException e) {
            logger.debug("Context event listener failed: {}", e.toString());
        }
    }

    private void emitLookup(SnapshotKey key, String result) {
        try {
            events.lookup(key, result);
        } catch (RuntimeException e) {
            logger.debug("Context event listener failed: {}", e.toString());
        }
    }

    public void close() {
        synchronized (lock) {
            pinned.values().forEach(this::cancelRetryLocked);
            onDemand.values().forEach(this::cancelRetryLocked);
        }
    }
}
