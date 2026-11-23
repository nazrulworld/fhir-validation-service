package nzi.fhir.validator.core.throttle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 014 (T007, research R3): the rolling window of one-second buckets. */
class WindowTest {

    private static final long T = 1_700_000_000L;

    @Test
    void countsTheLastSixtySecondsAndTheCurrentOne() {
        Window w = new Window();
        w.addLocal(T);
        w.addLocal(T);
        assertEquals(2, w.count(T));
        assertEquals(2, w.count(T + 60), "a bucket exactly 60 s old is still counted");
        assertEquals(0, w.count(T + 61), "a bucket 61 s old is not counted");
        assertEquals(0, w.count(T + 3600), "a stale slot is never counted");
    }

    @Test
    void slotsAreReusedAfterTheWindowMoves() {
        Window w = new Window();
        w.addLocal(T);
        w.addLocal(T + 61); // same slot as T, which has left the window
        assertEquals(1, w.count(T + 61));
    }

    @Test
    void localAndRemoteAreSummedAndRemoteIsReplaced() {
        Window w = new Window();
        w.addLocal(T);
        w.setRemote(T, 3, T);
        assertEquals(4, w.count(T));
        w.setRemote(T, 2, T);
        assertEquals(3, w.count(T), "setRemote replaces, never adds");
        assertEquals(1, w.localCount(T));
        w.setRemote(T - 70, 5, T);
        assertEquals(3, w.count(T), "a remote second outside the window is ignored");
        w.setRemote(T + 2, 5, T);
        assertEquals(3, w.count(T), "a remote second in this instance's future is ignored (read again later)");
    }

    @Test
    void remoteForANewerSecondDoesNotDropLocal() {
        Window w = new Window();
        w.addLocal(T);
        w.setRemote(T + 1, 2, T + 1);
        w.addLocal(T + 1);
        assertEquals(4, w.count(T + 1));
        assertEquals(2, w.localCount(T + 1));
    }

    @Test
    void secondsUntilBelowIsTheExactWait() {
        Window w = new Window();
        w.addLocal(T);      // leaves the window at T + 61
        w.addLocal(T + 10); // leaves at T + 71
        w.addLocal(T + 10);
        // cap 3, all used at T + 20: one slot is free once the bucket of T has left (T + 61), 41 s from now.
        assertEquals(41, w.secondsUntilBelow(3, T + 20));
        // cap 2 (count 3 > cap): two must leave; that happens when the T + 10 bucket leaves, at T + 71.
        assertEquals(51, w.secondsUntilBelow(2, T + 20));
        assertEquals(0, w.secondsUntilBelow(4, T + 20), "room now");
        assertEquals(0, new Window().secondsUntilBelow(1, T));
    }

    @Test
    void resetSecondsIsWhenTheOldestCountedRequestLeaves() {
        Window w = new Window();
        assertEquals(0, w.resetSeconds(T), "nothing counted");
        w.addLocal(T);
        w.addLocal(T + 30);
        assertEquals(61, w.resetSeconds(T));
        assertEquals(31, w.resetSeconds(T + 30));
        assertEquals(30, w.resetSeconds(T + 61), "the T bucket has left; the T + 30 one is the oldest");
    }

    /** Spec Edge Cases: a burst just before and just after a minute boundary does not get twice the cap. */
    @Test
    void noDoubledBurstAcrossAMinuteBoundary() {
        int cap = 5;
        Window w = new Window();
        long minute = T - Math.floorMod(T, 60);
        int admitted = 0;
        for (long sec : new long[]{minute + 59, minute + 61}) {
            for (int i = 0; i < cap; i++) {
                if (w.count(sec) + 1 <= cap) {
                    w.addLocal(sec);
                    admitted++;
                }
            }
        }
        assertEquals(cap, admitted);
        assertTrue(w.secondsUntilBelow(cap, minute + 61) > 0);
    }
}
