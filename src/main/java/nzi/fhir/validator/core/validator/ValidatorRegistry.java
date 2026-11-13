package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
 * </ul>
 * There are no other contexts (spec 007 FR-021): a validation request never builds one. A request naming an IG that
 * isn't included is refused before it gets here ({@link IgReference}); {@link #snapshot} refuses it again.
 * <p>
 * All state is guarded by one lock; critical sections are short (builds and promise completion happen outside it).
 */
public class ValidatorRegistry {
    private static final Logger logger = LogManager.getLogger(ValidatorRegistry.class);
    private static final long INITIAL_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 60_000;

    public enum Status { ABSENT, BUILDING, ACTIVE, STALE }

    public enum Kind { BASE, PINNED }

    public static final String BUILD_SUCCESS = "success";
    public static final String BUILD_FAILURE = "failure";
    public static final String BUILD_REFUSED_CAPACITY = "refused_capacity";
    public static final String EVICT_EXCLUDED = "excluded";
    public static final String LOOKUP_HIT = "hit";

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
        long retryDelayMs = INITIAL_RETRY_MS;
        long retryTimer = -1;
        volatile long lastUsed;
        final List<Promise<Void>> applyWaiters = new ArrayList<>();

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
    /** The versions this instance serves (spec 009); others are answered with the served list (FR-005). */
    private final nzi.fhir.validator.core.enums.ServedVersions served;
    /**
     * Base and pinned slots. Concurrent so validation can read them without the registry lock (SC-007); the map and
     * the slots' fields are still only written under the lock.
     */
    private final Map<SnapshotKey, Slot> pinned = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<SupportedFhirVersion, VersionState> versions = new EnumMap<>(SupportedFhirVersion.class);
    private volatile Supplier<PackageCatalogView> catalog = () -> PackageCatalogView.EMPTY;
    /** Highest catalog revision {@link #onCatalogChanged} has reconciled (guarded by the lock). */
    private long reconciledCatalogRevision;
    private volatile Consumer<SupportedFhirVersion> buildFailureListener = v -> { };
    private volatile Events events = Events.NONE;
    /**
     * Whether a context may be listed on the unauthenticated health endpoint (spec 005 research R12): its access and
     * every package's access are shareable. Called under the lock: must be quick and must not call back.
     */
    private volatile java.util.function.BiPredicate<SnapshotKey, List<String>> isPublic = (key, packages) -> true;

    public ValidatorRegistry(Vertx vertx, SnapshotBuilder builder) {
        this(vertx, builder, nzi.fhir.validator.core.enums.ServedVersions.ALL);
    }

    /**
     * @param served the FHIR versions this instance serves (spec 009 FR-004): only these get a base context, so
     *               readiness covers only them and nothing is loaded for the others
     */
    public ValidatorRegistry(Vertx vertx, SnapshotBuilder builder, nzi.fhir.validator.core.enums.ServedVersions served) {
        this.vertx = vertx;
        this.builder = builder;
        this.served = served;
        for (SupportedFhirVersion v : served.asList()) {
            versions.put(v, new VersionState());
            SnapshotKey base = SnapshotKey.base(v);
            pinned.put(base, new Slot(base, Kind.BASE));
        }
    }

    public void setBuildFailureListener(Consumer<SupportedFhirVersion> listener) {
        this.buildFailureListener = listener;
    }

    /** Source of the current package metadata, used to reconcile contexts with replaced packages. */
    public void setCatalog(Supplier<PackageCatalogView> catalog) {
        this.catalog = catalog;
    }

    public void setPublicFilter(java.util.function.BiPredicate<SnapshotKey, List<String>> isPublic) {
        this.isPublic = isPublic == null ? (key, packages) -> true : isPublic;
    }

    public void setEvents(Events events) {
        this.events = events == null ? Events.NONE : events;
    }

    private VersionState versionState(SupportedFhirVersion version) {
        VersionState state = versions.get(version);
        if (state == null) {
            throw ProblemException.unsupportedVersion(version.name(), served);
        }
        return state;
    }

    // --- serving -----------------------------------------------------------------------------------------------

    /**
     * The snapshot of a base or pinned context, without building anything. While a newer
     * configuration is being built, the previous snapshot keeps serving; a STALE or absent context answers 503.
     */
    public ValidatorSnapshot current(SnapshotKey key) {
        synchronized (lock) {
            versionState(key.fhirVersion());
            Slot slot = pinned.get(key);
            if (slot == null) {
                throw ProblemException.notReady("The validator for " + key + " is not ready yet");
            }
            return serveable(slot);
        }
    }

    /**
     * The packages of a built base or pinned context, without taking the registry lock (spec 005: the access check
     * runs before every validation, so it must stay off the lock like the hot path itself). Empty for contexts not
     * built yet: the caller resolves those from the catalog view as the context's owner, which gives the same set.
     */
    public java.util.Optional<List<String>> peekResolvedPackages(SnapshotKey key) {
        Slot s = pinned.get(key);
        ValidatorSnapshot snapshot = s == null ? null : s.snapshot;
        return snapshot == null ? java.util.Optional.empty() : java.util.Optional.of(snapshot.resolvedPackages());
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
     * The snapshot to validate with. Base and pinned contexts answer from memory (or 503 while not ready). Any other
     * key is refused with 400: validation never builds a context (spec 007 FR-021). {@link IgReference} normally
     * refuses such a request first; this is the second line of defence.
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
        synchronized (lock) {
            try {
                versionState(key.fhirVersion());
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
            Slot slot = pinned.get(key);
            if (slot == null && !key.isBase()) {
                return Future.failedFuture(ProblemException.igNotIncluded(key.igPackage(), key.fhirVersion()));
            }
            try {
                if (slot == null) {
                    throw ProblemException.notReady("The validator for " + key + " is not ready yet");
                }
                slot.lastUsed = System.currentTimeMillis();
                ValidatorSnapshot serving = serveable(slot);
                emitLookup(key, LOOKUP_HIT);
                return Future.succeededFuture(serving);
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
        }
    }

    // --- pinned configuration ---------------------------------------------------------------------------------

    /**
     * Applies configuration revision {@code revision} of {@code version}: the base context plus one pinned context per
     * entry of {@code pinnedContexts} ({@code name#version} → its resolved packages). New keys are built, keys whose
     * packages or contents changed are rebuilt (the old snapshot keeps serving meanwhile), unchanged keys are left
     * alone, and keys no longer included are dropped (spec 007 FR-021; a request already holding the snapshot
     * finishes with it). Completes
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

            // Excluded keys are dropped: there are no on-demand contexts to demote them to (spec 007 FR-021).
            for (Iterator<Map.Entry<SnapshotKey, Slot>> it = pinned.entrySet().iterator(); it.hasNext(); ) {
                Slot s = it.next().getValue();
                if (s.kind == Kind.PINNED && s.key.fhirVersion() == version && !desired.containsKey(s.key)) {
                    it.remove();
                    cancelRetryLocked(s);
                    evicted.add(s.key);
                    logger.info("Validator context {} is no longer included; dropped", s.key);
                }
            }

            for (Map.Entry<SnapshotKey, ResolvedContext> e : desired.entrySet()) {
                SnapshotKey key = e.getKey();
                ResolvedContext ctx = e.getValue();
                Slot s = pinned.get(key);
                if (s == null) {
                    s = new Slot(key, key.isBase() ? Kind.BASE : Kind.PINNED);
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
     * content was replaced stop serving (STALE) until rebuilt by the next {@link #applyPinned}.
     */
    public void onCatalogChanged(PackageCatalogView view) {
        // A sync may deliver a view older than one a request has already built from (a lookup miss can refresh the
        // catalog in between): reconcile with the newest view, and never with one older than already reconciled.
        PackageCatalogView latest = catalog.get();
        if (latest != null && latest.revision() > view.revision()) {
            view = latest;
        }
        synchronized (lock) {
            if (view.revision() < reconciledCatalogRevision) {
                return;
            }
            reconciledCatalogRevision = view.revision();
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
        }
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
            future = builder.build(slot.key, revision, resolved);
        } catch (RuntimeException e) {
            future = Future.failedFuture(e);
        }
        future.onComplete(ar -> {
            List<Promise<Void>> applyDone = List.of();
            boolean again;
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
                } else {
                    slot.status = Status.STALE;
                    slot.reason = describe(ar.cause());
                    again = slot.dirty;
                    retry = !again;
                }
                if (!again) {
                    slot.building = false;
                    applyDone = new ArrayList<>(slot.applyWaiters);
                    slot.applyWaiters.clear();
                }
            }
            if (ar.succeeded()) {
                emitBuild(slot.key, kind, BUILD_SUCCESS);
            } else {
                emitBuild(slot.key, kind, ar.cause() instanceof HeapBudget.InsufficientHeapException
                        ? BUILD_REFUSED_CAPACITY : BUILD_FAILURE);
                logger.error("Validator build for {} at revision {} failed", slot.key, revision, ar.cause());
                buildFailureListener.accept(slot.key.fhirVersion());
            }
            if (again) {
                build(slot);
                return;
            }
            if (ar.succeeded()) {
                applyDone.forEach(Promise::tryComplete);
            } else {
                applyDone.forEach(p -> p.tryFail(ar.cause()));
                if (retry) {
                    scheduleRetry(slot);
                }
            }
        });
    }

    private void scheduleRetry(Slot slot) {
        long delay;
        synchronized (lock) {
            if (slot.retryTimer >= 0 || vertx == null) {
                return;
            }
            delay = slot.retryDelayMs;
            slot.retryDelayMs = Math.min(slot.retryDelayMs * 2, MAX_RETRY_MS);
            slot.retryTimer = vertx.setTimer(delay, id -> {
                boolean start;
                synchronized (lock) {
                    slot.retryTimer = -1;
                    start = pinned.get(slot.key) == slot && !slot.building
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
     * @return empty when every base and pinned context can serve requests
     */
    public List<String> notReadyReasons() {
        List<String> reasons = new ArrayList<>();
        synchronized (lock) {
            pinned.values().stream()
                    .sorted((a, b) -> a.key.toString().compareTo(b.key.toString()))
                    .forEach(slot -> {
                        // A private context is counted, never named (spec 005 FR-021): readiness is unauthenticated.
                        String label = publicLocked(slot) ? label(slot.key) + " validator"
                                : "a private IG validator context for " + slot.key.fhirVersion().name();
                        if (slot.status == Status.STALE) {
                            reasons.add(label + " is out of date" + (publicLocked(slot) ? ": " + slot.reason : ""));
                        } else if (slot.snapshot == null) {
                            reasons.add(label + " is " + slot.status.name().toLowerCase());
                        }
                    });
        }
        return reasons;
    }

    /** The packages a slot holds or is being built from, for the public filter. */
    private boolean publicLocked(Slot slot) {
        if (slot.key.isBase()) {
            return true;
        }
        List<String> packages = slot.snapshot != null ? slot.snapshot.resolvedPackages() : slot.wanted.packages();
        try {
            return isPublic.test(slot.key, packages);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String label(SnapshotKey key) {
        return key.isBase() ? key.fhirVersion().name() : key.toString();
    }

    public boolean isReady() {
        return notReadyReasons().isEmpty();
    }

    /**
     * Per version: {@code {base: {...}, contexts: [{key, kind, state, ...}], privateContexts: {count, states}}}
     * (contracts/http-api.md §5; spec 005 §8: contexts that aren't fully shareable are only counted, and owners are
     * never listed).
     */
    public JsonObject details() {
        JsonObject details = new JsonObject();
        synchronized (lock) {
            for (SupportedFhirVersion version : versions.keySet()) {
                JsonArray contexts = new JsonArray();
                List<Slot> slots = new ArrayList<>();
                pinned.values().stream().filter(s -> s.key.fhirVersion() == version && !s.key.isBase()).forEach(slots::add);
                slots.sort((a, b) -> a.key.igPackage().compareTo(b.key.igPackage()));
                JsonObject privateStates = new JsonObject();
                for (Status st : Status.values()) {
                    privateStates.put(st.name(), 0);
                }
                int privateCount = 0;
                for (Slot s : slots) {
                    if (publicLocked(s)) {
                        // Spec 007 FR-004: the setting the context is built (or being built) with.
                        ResolvedContext shown = s.building || s.activeResolved == null ? s.wanted : s.activeResolved;
                        contexts.add(describeSlot(s).put("key", s.key.igPackage()).put("kind", "pinned")
                                .put("terminology", shown.terminology().toJson()));
                    } else {
                        privateCount++;
                        privateStates.put(s.status.name(), privateStates.getInteger(s.status.name()) + 1);
                    }
                }
                VersionState state = versions.get(version);
                details.put(version.name(), new JsonObject()
                        .put("appliedRevision", state.appliedRevision)
                        .put("latestRevision", state.latestSeenRevision)
                        .put("base", describeSlot(pinned.get(SnapshotKey.base(version))))
                        .put("contexts", contexts)
                        .put("privateContexts", new JsonObject().put("count", privateCount).put("states", privateStates)));
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
            return pinned.values().stream().filter(s -> s.key.fhirVersion() == version && s.kind == kind).count();
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
            return s == null ? Status.ABSENT : s.status;
        }
    }

    /** Kind of the context {@code key} on this instance, or {@code null} when there is none. */
    public Kind kind(SnapshotKey key) {
        synchronized (lock) {
            Slot s = pinned.get(key);
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
        }
    }
}
