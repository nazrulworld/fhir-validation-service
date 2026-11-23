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
     * T054 (Constitution I): the windows changed since the last flush, each at most once, so a sync tick costs what
     * changed, not every tracked window.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Changed> changed = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile int lastDrained;

    /** A window queued for the next flush, with the subject it counts. */
    record Changed(SubjectKey key, Window window) {
    }
    private final long syncIntervalMs;
    private final LongAdder evictions = new LongAdder();

    /** Live peers seen by the last sync; 0 for one instance and in local mode. */
    private volatile int peers;
    /** Research R4: admissions are tagged with the flush generation they will be written in. */
    private volatile long generation;
    /** Admissions of generations up to this one are visible to every live peer. */
    private volatile long confirmedThrough = Long.MIN_VALUE;

    /**
     * @param maxTracked     most windows held ({@code FV_THROTTLE_MAX_TRACKED})
     * @param syncIntervalMs {@code FV_THROTTLE_SYNC_INTERVAL_MS}: an admission is confirmed within two of these
     */
    public Throttle(int maxTracked, long syncIntervalMs) {
        this.syncIntervalMs = syncIntervalMs;
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
        Window keyWindow = keyCap.isPresent() && keyId != null ? window(KEY, keyId) : null;
        Window orgWindow = orgCap.isPresent() && orgId != null ? window(ORG, orgId) : null;
        if (keyWindow == null && orgWindow == null) {
            return ThrottleDecision.unlimited();
        }
        // Fixed lock order: key, then organisation (research R3).
        Object first = keyWindow != null ? keyWindow : orgWindow;
        Object second = keyWindow != null && orgWindow != null ? orgWindow : null;
        ThrottleDecision d;
        synchronized (first) {
            if (second == null) {
                d = decide(keyWindow, keyCap, orgWindow, orgCap, now, p);
            } else {
                synchronized (second) {
                    d = decide(keyWindow, keyCap, orgWindow, orgCap, now, p);
                }
            }
        }
        if (d.admitted()) {
            // After the locks: a flush that drained the window in between only finds nothing new (harmless).
            queue(KEY, keyId, keyWindow);
            queue(ORG, orgId, orgWindow);
        }
        return d;
    }

    private void queue(char scope, UUID id, Window w) {
        if (w != null && w.enqueue()) {
            changed.add(new Changed(new SubjectKey(scope, id), w));
        }
    }

    /** T054: hands every window changed since the last call to {@code action}, each once. */
    void drainChanged(BiConsumer<SubjectKey, Window> action) {
        int n = 0;
        for (Changed c; (c = changed.poll()) != null; n++) {
            action.accept(c.key(), c.window());
        }
        lastDrained = n;
    }

    /** Queues a window again (its flush failed). */
    void requeue(SubjectKey key, Window w) {
        if (w.enqueue()) {
            changed.add(new Changed(key, w));
        }
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

    private ThrottleDecision decide(Window keyWindow, OptionalInt keyCap, Window orgWindow, OptionalInt orgCap,
                                    long now, int p) {
        Level key = keyWindow == null ? null : level(Scope.KEY, keyWindow, keyCap.getAsInt(), now, p);
        Level org = orgWindow == null ? null : level(Scope.ORGANISATION, orgWindow, orgCap.getAsInt(), now, p);
        boolean keyOk = key == null || key.admits();
        boolean orgOk = org == null || org.admits();
        if (keyOk && orgOk) {
            long gen = generation;
            if (keyWindow != null) {
                keyWindow.addLocal(now);
                keyWindow.addPending(gen);
            }
            if (orgWindow != null) {
                orgWindow.addLocal(now);
                orgWindow.addPending(gen);
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

    void confirmThrough(long generation) {
        this.confirmedThrough = generation;
    }
}
