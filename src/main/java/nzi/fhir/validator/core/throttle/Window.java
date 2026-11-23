package nzi.fhir.validator.core.throttle;

/**
 * The recent validation requests of one subject, an API key or an organisation (spec 014, research R3): a ring of
 * {@value #SLOTS} one-second buckets. Each bucket holds what this instance admitted ({@code local}) and what the other
 * instances admitted, as last read from the database ({@code remote}). The windowed count at second {@code now} is the
 * sum over the buckets of the seconds {@code now - 60 .. now}. Whole seconds make the window up to one second longer
 * than 60 s, so the count is never lower than an exact rolling minute's: a cap is never exceeded on one instance.
 * <p>
 * Every method is {@code synchronized} and does no I/O: windows are touched from every HTTP event loop, and a request
 * holds at most two of them (its key's, then its organisation's; never the other way round).
 */
public final class Window {
    /** 61 buckets: the current second and the 60 before it. */
    static final int SLOTS = 61;

    private final long[] seconds = new long[SLOTS];
    private final int[] local = new int[SLOTS];
    private final int[] remote = new int[SLOTS];
    /** Whether a slot's {@code local} count changed since the last {@link #drainDirty} (spec 014 research R4). */
    private final boolean[] dirty = new boolean[SLOTS];

    // Research R4: admissions not yet visible to every peer, per flush generation (see Throttle).
    private final long[] pendingGeneration = new long[3];
    private final int[] pendingCount = new int[3];

    // FR-018: the bounded refusal log (at most one line per subject per minute).
    private long lastLogAtMs = Long.MIN_VALUE;
    private int refusedSinceLog;
    private String firstRefusedRequestId;

    public Window() {
        java.util.Arrays.fill(seconds, Long.MIN_VALUE);
        java.util.Arrays.fill(pendingGeneration, Long.MIN_VALUE);
    }

    private static int slot(long second) {
        return (int) Math.floorMod(second, SLOTS);
    }

    private static boolean inWindow(long second, long now) {
        return second <= now && second >= now - (SLOTS - 1);
    }

    /** The slot for {@code second}, cleared first if it still holds an older second. */
    private int claim(long second) {
        int i = slot(second);
        if (seconds[i] != second) {
            seconds[i] = second;
            local[i] = 0;
            remote[i] = 0;
            dirty[i] = false;
        }
        return i;
    }

    /** Requests counted (local + remote) in the seconds {@code now - 60 .. now}. */
    public synchronized int count(long now) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (inWindow(seconds[i], now)) {
                n += local[i] + remote[i];
            }
        }
        return n;
    }

    /** Requests this instance admitted in the seconds {@code now - 60 .. now}. */
    public synchronized int localCount(long now) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (inWindow(seconds[i], now)) {
                n += local[i];
            }
        }
        return n;
    }

    /** One request admitted by this instance at second {@code now}. */
    public synchronized void addLocal(long now) {
        int i = claim(now);
        local[i]++;
        dirty[i] = true;
    }

    /**
     * The other instances' total for {@code second}, replacing what was there (reads are idempotent). A second outside
     * the window is ignored, and so is a second after {@code now}: a peer whose clock runs ahead is read again on a
     * later tick, once that second is no longer in this instance's future.
     */
    public synchronized void setRemote(long second, int count, long now) {
        if (!inWindow(second, now)) {
            return;
        }
        int i = slot(second);
        if (seconds[i] > second) {
            return;
        }
        remote[claim(second)] = count;
    }

    /**
     * Seconds until the windowed count, without new requests, is at most {@code cap - 1}, so one more fits; 0 when it
     * already fits. Exact on one instance (FR-009).
     */
    public synchronized int secondsUntilBelow(int cap, long now) {
        for (int t = 0; t <= SLOTS; t++) {
            long from = now + t - (SLOTS - 1);
            int n = 0;
            for (int i = 0; i < SLOTS; i++) {
                if (seconds[i] >= from && seconds[i] <= now) {
                    n += local[i] + remote[i];
                }
            }
            if (n <= cap - 1) {
                return t;
            }
        }
        return SLOTS;
    }

    /** Seconds until the oldest counted request leaves the window (the allowance next grows); 0 when none is counted. */
    public synchronized int resetSeconds(long now) {
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (inWindow(seconds[i], now) && local[i] + remote[i] > 0) {
                oldest = Math.min(oldest, seconds[i]);
            }
        }
        return oldest == Long.MAX_VALUE ? 0 : (int) (oldest + SLOTS - now);
    }

    /**
     * Research R4: the local counts changed since the last call, as {@code [second, count]} pairs within the window,
     * and clears the marks. The counts are absolute (this instance's total for that second), so a flush that is
     * retried or repeated is harmless.
     */
    public synchronized long[][] drainDirty(long now) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (dirty[i] && inWindow(seconds[i], now)) {
                n++;
            }
        }
        long[][] out = new long[n][];
        int k = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (dirty[i]) {
                if (inWindow(seconds[i], now)) {
                    out[k++] = new long[]{seconds[i], local[i]};
                }
                dirty[i] = false;
            }
        }
        return out;
    }

    /** Marks {@code second}'s local count as changed again (its flush failed). */
    public synchronized void markDirty(long second, long now) {
        int i = slot(second);
        if (seconds[i] == second && inWindow(second, now) && local[i] > 0) {
            dirty[i] = true;
        }
    }

    /** Research R4: one admission in flush generation {@code generation}. */
    synchronized void addPending(long generation) {
        int i = (int) Math.floorMod(generation, pendingGeneration.length);
        if (pendingGeneration[i] != generation) {
            pendingGeneration[i] = generation;
            pendingCount[i] = 0;
        }
        pendingCount[i]++;
    }

    /** Research R4: admissions of generations after {@code confirmedThrough}, i.e. not yet visible to every peer. */
    synchronized int unconfirmed(long confirmedThrough) {
        int n = 0;
        for (int i = 0; i < pendingGeneration.length; i++) {
            if (pendingGeneration[i] != Long.MIN_VALUE && pendingGeneration[i] > confirmedThrough) {
                n += pendingCount[i];
            }
        }
        return n;
    }

    /** FR-018: a refusal log line to write: how many refusals it reports, and the request id of the first. */
    record LogLine(int refusals, String firstRequestId) {
    }

    /**
     * FR-018: records one refusal and says whether to log now. The first refusal after a quiet minute logs at once;
     * later ones in that minute are only counted, and the next refusal after the minute logs the count.
     *
     * @return the line to log, or {@code null} to stay quiet
     */
    synchronized LogLine refusal(long nowMs, String requestId) {
        if (refusedSinceLog == 0) {
            firstRefusedRequestId = requestId;
        }
        refusedSinceLog++;
        if (lastLogAtMs == Long.MIN_VALUE || nowMs - lastLogAtMs >= 60_000) {
            LogLine line = new LogLine(refusedSinceLog, firstRefusedRequestId);
            lastLogAtMs = nowMs;
            refusedSinceLog = 0;
            return line;
        }
        return null;
    }
}
