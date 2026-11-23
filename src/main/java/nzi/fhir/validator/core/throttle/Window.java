package nzi.fhir.validator.core.throttle;

/**
 * The recent validation requests of one subject, an API key or an organisation (spec 014, research R3): a ring of
 * {@value #SLOTS} one-second buckets. Each bucket holds what this instance admitted ({@code local}) and what the other
 * instances admitted, as last read from the database ({@code remote}). The windowed count at second {@code now} is the
 * sum over the buckets of the seconds {@code now - 60 .. now}. Whole seconds make the window up to one second longer
 * than 60 s, so the count is never lower than an exact rolling minute's: a cap is never exceeded on one instance.
 * <p>
 * Compact (SC-008, T055): the ring remembers only its newest second ({@code head}); a slot belongs to the one second
 * of {@code head - 60 .. head} it maps to, and slots are cleared as {@code head} moves on. Counts are kept as
 * {@code char} and widened to {@code int} only when a second's count exceeds 65 535; the remote counts are allocated
 * when a peer first reports one; the changed-since-flush marks are one bit each.
 * <p>
 * Every method is {@code synchronized} and does no I/O: windows are touched from every HTTP event loop, and a request
 * holds at most two of them (its key's, then its organisation's; never the other way round).
 */
public final class Window {
    /** 61 buckets: the current second and the 60 before it. */
    static final int SLOTS = 61;
    private static final int CHAR_MAX = 0xFFFF;

    /** The newest second the ring holds; {@link Long#MIN_VALUE} while empty. */
    private long head = Long.MIN_VALUE;
    private char[] localC = new char[SLOTS];
    private int[] localW;
    private char[] remoteC;
    private int[] remoteW;
    /** Bit i: slot i's local count changed since the last {@link #drainDirty} (research R4). */
    private long dirty;

    // Research R4: admissions not yet visible to every peer. An admission is "unflushed" until a drain (under this
    // window's lock) moves it into the bucket of the flush generation that writes it, so it can never be confirmed
    // before it was flushed. Two buckets suffice: a generation is confirmed one tick after its flush.
    private int unflushed;
    private long genA = Long.MIN_VALUE, genB = Long.MIN_VALUE;
    private int countA, countB;

    // FR-018: the bounded refusal log (at most one line per subject per minute).
    private long lastLogAtMs = Long.MIN_VALUE;
    private int refusedSinceLog;
    private String firstRefusedRequestId;

    private static int slot(long second) {
        return (int) Math.floorMod(second, SLOTS);
    }

    // ---- counts ----

    private int local(int i) {
        return localW != null ? localW[i] : localC[i];
    }

    private void local(int i, int v) {
        if (localW != null) {
            localW[i] = v;
        } else if (v <= CHAR_MAX) {
            localC[i] = (char) v;
        } else {
            localW = widen(localC);
            localC = null;
            localW[i] = v;
        }
    }

    private int remote(int i) {
        return remoteW != null ? remoteW[i] : remoteC != null ? remoteC[i] : 0;
    }

    private void remote(int i, int v) {
        if (remoteW != null) {
            remoteW[i] = v;
        } else if (v <= CHAR_MAX) {
            if (remoteC == null) {
                if (v == 0) {
                    return;
                }
                remoteC = new char[SLOTS];
            }
            remoteC[i] = (char) v;
        } else {
            remoteW = remoteC == null ? new int[SLOTS] : widen(remoteC);
            remoteC = null;
            remoteW[i] = v;
        }
    }

    private static int[] widen(char[] c) {
        int[] w = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            w[i] = c[i];
        }
        return w;
    }

    private void clear(int i) {
        local(i, 0);
        remote(i, 0);
        dirty &= ~(1L << i);
    }

    /** Moves the ring's newest second to {@code second}, clearing the slots of the seconds that left. */
    private void advance(long second) {
        if (head != Long.MIN_VALUE && second <= head) {
            return;
        }
        if (head == Long.MIN_VALUE || second - head >= SLOTS) {
            for (int i = 0; i < SLOTS; i++) {
                clear(i);
            }
        } else {
            for (long s = head + 1; s <= second; s++) {
                clear(slot(s));
            }
        }
        head = second;
    }

    /** Whether {@code second} is one of the seconds the ring holds. */
    private boolean held(long second) {
        return head != Long.MIN_VALUE && second <= head && second > head - SLOTS;
    }

    /** Sum of local + remote over the held seconds {@code from .. to}. */
    private int sum(long from, long to) {
        if (head == Long.MIN_VALUE) {
            return 0;
        }
        long lo = Math.max(from, head - SLOTS + 1);
        long hi = Math.min(to, head);
        int n = 0;
        for (long s = lo; s <= hi; s++) {
            int i = slot(s);
            n += local(i) + remote(i);
        }
        return n;
    }

    /** Requests counted (local + remote) in the seconds {@code now - 60 .. now}. */
    public synchronized int count(long now) {
        return sum(now - (SLOTS - 1), now);
    }

    /** Requests this instance admitted in the seconds {@code now - 60 .. now}. */
    public synchronized int localCount(long now) {
        if (head == Long.MIN_VALUE) {
            return 0;
        }
        int n = 0;
        for (long s = Math.max(now - (SLOTS - 1), head - SLOTS + 1); s <= Math.min(now, head); s++) {
            n += local(slot(s));
        }
        return n;
    }

    /** One request admitted by this instance at second {@code now}. */
    public synchronized void addLocal(long now) {
        advance(now);
        if (!held(now)) {
            return; // a clock that jumped back more than a minute: nothing sensible to count it in
        }
        int i = slot(now);
        local(i, local(i) + 1);
        dirty |= 1L << i;
    }

    /**
     * The other instances' total for {@code second}, replacing what was there (reads are idempotent). A second outside
     * the window is ignored, and so is a second after {@code now}: a peer whose clock runs ahead is read again on a
     * later tick, once that second is no longer in this instance's future.
     */
    public synchronized void setRemote(long second, int count, long now) {
        if (second > now || second < now - (SLOTS - 1)) {
            return;
        }
        advance(second);
        if (held(second)) {
            remote(slot(second), count);
        }
    }

    /**
     * Seconds until the windowed count, without new requests, is at most {@code cap - 1}, so one more fits; 0 when it
     * already fits. Exact on one instance (FR-009).
     */
    public synchronized int secondsUntilBelow(int cap, long now) {
        for (int t = 0; t <= SLOTS; t++) {
            if (sum(now + t - (SLOTS - 1), now) <= cap - 1) {
                return t;
            }
        }
        return SLOTS;
    }

    /** Seconds until the oldest counted request leaves the window (the allowance next grows); 0 when none is counted. */
    public synchronized int resetSeconds(long now) {
        if (head == Long.MIN_VALUE) {
            return 0;
        }
        for (long s = Math.max(now - (SLOTS - 1), head - SLOTS + 1); s <= Math.min(now, head); s++) {
            int i = slot(s);
            if (local(i) + remote(i) > 0) {
                return (int) (s + SLOTS - now);
            }
        }
        return 0;
    }

    /**
     * Research R4: the local counts changed since the last call, as {@code [second, count]} pairs within the window,
     * and clears the marks. The counts are absolute (this instance's total for that second), so a flush that is
     * retried or repeated is harmless. The admissions counted since the last drain move into flush generation
     * {@code generation}'s bucket: they are written by that flush, and confirmed once {@code generation} is.
     *
     * @param confirmedThrough the generations confirmed so far; an older bucket that isn't is merged, never dropped
     */
    public synchronized long[][] drainDirty(long now, long generation, long confirmedThrough) {
        movePending(generation, confirmedThrough);
        if (dirty == 0 || head == Long.MIN_VALUE) {
            dirty = 0;
            return new long[0][];
        }
        long[][] out = new long[Long.bitCount(dirty)][];
        int k = 0;
        for (long s = head - SLOTS + 1; s <= head; s++) {
            int i = slot(s);
            if ((dirty & (1L << i)) != 0 && s >= now - (SLOTS - 1) && s <= now) {
                out[k++] = new long[]{s, local(i)};
            }
        }
        dirty = 0;
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }

    private void movePending(long generation, long confirmedThrough) {
        if (unflushed == 0) {
            return;
        }
        boolean a = (generation & 1) == 0;
        long old = a ? genA : genB;
        int carried = old == generation || (old != Long.MIN_VALUE && old > confirmedThrough) ? (a ? countA : countB) : 0;
        if (a) {
            genA = generation;
            countA = carried + unflushed;
        } else {
            genB = generation;
            countB = carried + unflushed;
        }
        unflushed = 0;
    }

    /** Marks {@code second}'s local count as changed again (its flush failed). */
    public synchronized void markDirty(long second, long now) {
        if (held(second) && second >= now - (SLOTS - 1) && local(slot(second)) > 0) {
            dirty |= 1L << slot(second);
        }
    }

    /** Research R4: one admission, unflushed until the next drain of this window. */
    synchronized void addPending() {
        unflushed++;
    }

    /** Research R4: admissions not yet visible to every peer: unflushed, or flushed in a generation after the one given. */
    synchronized int unconfirmed(long confirmedThrough) {
        return unflushed + (genA != Long.MIN_VALUE && genA > confirmedThrough ? countA : 0)
                + (genB != Long.MIN_VALUE && genB > confirmedThrough ? countB : 0);
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
