package nzi.fhir.validator.core.throttle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import nzi.fhir.validator.core.throttle.ThrottleDecision.Reason;
import nzi.fhir.validator.core.throttle.ThrottleDecision.Scope;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

/**
 * The check-and-count of spec 014 (research R3, R4): one window per API key and per organisation, and the admission
 * rule. No I/O: the request path calls {@link #check} only; {@link ThrottleSync} moves counts to and from the database.
 * <p>
 * Admission at one level with cap {@code c}, windowed count {@code V}, {@code p} live peers:
 * <ul>
 *     <li>{@code p = 0} (one instance, or local mode): admit iff {@code V + 1 <= c}, exact.</li>
 *     <li>{@code p >= 1}: {@code B = max(1, ceil(c / 10))}, {@code r = max(1, floor(B / p))}; admit iff
 *     {@code V + 1 + p·r <= c + B} and this instance's unconfirmed admissions are fewer than {@code r}. What peers can
 *     have admitted without this instance seeing it is at most {@code p·r}, so the fleet total stays within
 *     {@code c + B} (SC-001). When {@code c + B - p·r} would be below 1 (a cap smaller than the number of instances),
 *     the threshold is 1, so a subject is never refused for ever; such tiny caps may then be exceeded by up to
 *     {@code p}.</li>
 * </ul>
 * A request is admitted only when every level with a cap admits it; then it is counted at every such level, under the
 * locks of both windows (the key's first, then the organisation's), so two concurrent requests can't both take the
 * last slot.
 */
public final class Throttle {
    public static final char KEY = 'K';
    public static final char ORG = 'O';

    /** A counted subject: an API key ({@link #KEY}) or an organisation ({@link #ORG}). */
    public record SubjectKey(char scope, UUID id) {
    }

    private final Cache<SubjectKey, Window> windows;
    /**
     * T054 (Constitution I): the subjects changed since the last flush, so a sync tick costs what changed, not every
     * tracked window. Keys, not windows: a flush always writes the window the cache holds now, so a window evicted and
     * recreated for the same subject is never written twice. Marked while the window's lock is held, so a drain sees
     * every admission or the next drain does. Bounded by {@link #maxChanged}; beyond it the next drain scans every
     * window once instead (code review of #99).
     */
    private final java.util.Set<SubjectKey> changed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final int maxChanged;
    private volatile boolean changedOverflow;
    private volatile int lastDrained;
    private final long syncIntervalMs;
    private final LongAdder evictions = new LongAdder();

    /** Live peers seen by the last sync; 0 for one instance and in local mode. */
    private volatile int peers;
    /** Research R4: the flush generation; a drain moves admissions into the generation it closes. */
    private volatile long generation;
    /** Admissions of generations up to this one are visible to every live peer. */
    private volatile long confirmedThrough = Long.MIN_VALUE;

    /**
     * @param maxTracked     most windows held ({@code FV_THROTTLE_MAX_TRACKED})
     * @param syncIntervalMs {@code FV_THROTTLE_SYNC_INTERVAL_MS}: an admission is confirmed within two of these
     */
    public Throttle(int maxTracked, long syncIntervalMs) {
        this.syncIntervalMs = syncIntervalMs;
        this.maxChanged = maxTracked;
        this.windows = Caffeine.newBuilder()
                .maximumSize(maxTracked)
                .expireAfterAccess(Duration.ofMinutes(2))
                .removalListener((SubjectKey k, Window w, RemovalCause cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictions.increment();
                    }
                })
                .build();
    }

    /**
     * Checks one validation request of key {@code keyId} (organisation {@code orgId}) at {@code nowMs} and counts it
     * when admitted.
     */
    public ThrottleDecision check(UUID keyId, UUID orgId, ThrottleCaps caps, long nowMs) {
        OptionalInt keyCap = caps.keyCap(keyId);
        OptionalInt orgCap = caps.orgCap(orgId);
        if (keyCap.isEmpty() && orgCap.isEmpty()) {
            return ThrottleDecision.unlimited();
        }
        long now = Math.floorDiv(nowMs, 1000);
        int p = peers;
        SubjectKey keyKey = keyCap.isPresent() && keyId != null ? new SubjectKey(KEY, keyId) : null;
        SubjectKey orgKey = orgCap.isPresent() && orgId != null ? new SubjectKey(ORG, orgId) : null;
        Window keyWindow = keyKey == null ? null : windows.get(keyKey, k -> new Window());
        Window orgWindow = orgKey == null ? null : windows.get(orgKey, k -> new Window());
        if (keyWindow == null && orgWindow == null) {
            return ThrottleDecision.unlimited();
        }
        // Fixed lock order: key, then organisation (research R3).
        Object first = keyWindow != null ? keyWindow : orgWindow;
        Object second = keyWindow != null && orgWindow != null ? orgWindow : null;
        synchronized (first) {
            if (second == null) {
                return decide(keyKey, keyWindow, keyCap, orgKey, orgWindow, orgCap, now, p);
            }
            synchronized (second) {
                return decide(keyKey, keyWindow, keyCap, orgKey, orgWindow, orgCap, now, p);
            }
        }
    }

    /** Marks {@code key} changed (called with its window's lock held, or after a failed flush). */
    void markChanged(SubjectKey key) {
        if (changedOverflow) {
            return;
        }
        if (changed.size() >= maxChanged) {
            changedOverflow = true;
            return;
        }
        changed.add(key);
    }

    /**
     * T054: hands every subject changed since the last call, with the window the cache holds for it now, to
     * {@code action}, each once. After an overflow, every tracked window instead (once).
     */
    void drainChanged(BiConsumer<SubjectKey, Window> action) {
        int n = 0;
        if (changedOverflow) {
            changedOverflow = false;
            changed.clear();
            windows.cleanUp(); // pending evictions first, so the scan covers at most FV_THROTTLE_MAX_TRACKED windows
            for (java.util.Map.Entry<SubjectKey, Window> e : windows.asMap().entrySet()) {
                action.accept(e.getKey(), e.getValue());
                n++;
            }
        } else {
            for (java.util.Iterator<SubjectKey> it = changed.iterator(); it.hasNext(); ) {
                SubjectKey key = it.next();
                it.remove();
                Window w = windows.getIfPresent(key);
                if (w != null) {
                    action.accept(key, w);
                    n++;
                }
            }
        }
        lastDrained = n;
    }

    /** Subjects marked changed and not drained yet (tests). */
    int changedSize() {
        return changed.size();
    }

    /** Tests: drops a subject's window, as an eviction by size or age would. */
    void evict(SubjectKey key) {
        windows.invalidate(key);
    }

    /** Windows the last {@link #drainChanged} visited (tests). */
    int lastDrained() {
        return lastDrained;
    }

    /** One level's verdict before counting. */
    private record Level(Scope scope, int cap, int count, boolean capRoom, boolean confirmedRoom, int threshold) {
        boolean admits() {
            return capRoom && confirmedRoom;
        }

        int remainingAfter(boolean admitted) {
            return Math.max(0, cap - count - (admitted ? 1 : 0));
        }
    }

    private Level level(Scope scope, Window w, int cap, long now, int p) {
        int v = w.count(now);
        if (p <= 0) {
            return new Level(scope, cap, v, v + 1 <= cap, true, cap);
        }
        int b = Math.max(1, (cap + 9) / 10);
        int r = Math.max(1, b / p);
        int threshold = Math.max(1, cap + b - p * r);
        boolean confirmedRoom = w.unconfirmed(confirmedThrough) < r;
        return new Level(scope, cap, v, v + 1 <= threshold, confirmedRoom, threshold);
    }

    private ThrottleDecision decide(SubjectKey keyKey, Window keyWindow, OptionalInt keyCap, SubjectKey orgKey,
                                    Window orgWindow, OptionalInt orgCap, long now, int p) {
        Level key = keyWindow == null ? null : level(Scope.KEY, keyWindow, keyCap.getAsInt(), now, p);
        Level org = orgWindow == null ? null : level(Scope.ORGANISATION, orgWindow, orgCap.getAsInt(), now, p);
        boolean keyOk = key == null || key.admits();
        boolean orgOk = org == null || org.admits();
        if (keyOk && orgOk) {
            // Under both windows' locks: a drain either sees this admission (counts, pending, mark) or the next one does.
            if (keyWindow != null) {
                keyWindow.addLocal(now);
                keyWindow.addPending();
                markChanged(keyKey);
            }
            if (orgWindow != null) {
                orgWindow.addLocal(now);
                orgWindow.addPending();
                markChanged(orgKey);
            }
            // Headers: the level with the least remaining; the key on a tie.
            Level shown = key == null ? org : org == null ? key
                    : org.remainingAfter(true) < key.remainingAfter(true) ? org : key;
            Window shownWindow = shown == key ? keyWindow : orgWindow;
            return new ThrottleDecision(true, true, shown.scope(), null, shown.cap(), shown.remainingAfter(true),
                    shownWindow.resetSeconds(now), 0);
        }
        // Refused: report the key when both refuse (with the longer wait), else the level that refused.
        int keyWait = keyOk ? 0 : waitFor(key, keyWindow, now);
        int orgWait = orgOk ? 0 : waitFor(org, orgWindow, now);
        Level shown = !keyOk ? key : org;
        Window shownWindow = shown == key ? keyWindow : orgWindow;
        Reason reason = shown.capRoom() ? Reason.UNCONFIRMED : Reason.CAP;
        return new ThrottleDecision(false, true, shown.scope(), reason, shown.cap(), shown.remainingAfter(false),
                shownWindow.resetSeconds(now), Math.max(1, Math.max(keyWait, orgWait)));
    }

    /** Seconds until a refused level admits again, without other traffic (FR-009). */
    private int waitFor(Level level, Window w, long now) {
        if (!level.capRoom()) {
            return Math.max(1, w.secondsUntilBelow(level.threshold(), now));
        }
        // Research R4: an admission is visible to every peer within two sync intervals.
        return confirmationSeconds();
    }

    /** {@code ceil(2 × sync interval)} in seconds: how long an admission takes to be confirmed. */
    public int confirmationSeconds() {
        return (int) Math.max(1, (2 * syncIntervalMs + 999) / 1000);
    }

    /** The window of a subject, created on first use. */
    public Window window(char scope, UUID id) {
        return windows.get(new SubjectKey(scope, id), k -> new Window());
    }

    /** The window of a subject if it is tracked. */
    Window existingWindow(char scope, UUID id) {
        return windows.getIfPresent(new SubjectKey(scope, id));
    }

    public long trackedSubjects() {
        windows.cleanUp();
        return windows.estimatedSize();
    }

    public long evictions() {
        return evictions.sum();
    }

    public int peers() {
        return peers;
    }

    void peers(int peers) {
        this.peers = Math.max(0, peers);
    }

    long generation() {
        return generation;
    }

    /** Starts a new flush generation and returns the one being closed. */
    long nextGeneration() {
        return generation++;
    }

    long confirmedThrough() {
        return confirmedThrough;
    }

    void confirmThrough(long generation) {
        this.confirmedThrough = generation;
    }
}
