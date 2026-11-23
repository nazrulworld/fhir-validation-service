package nzi.fhir.validator.core.throttle;

import nzi.fhir.validator.core.throttle.ThrottleDecision.Reason;
import nzi.fhir.validator.core.throttle.ThrottleDecision.Scope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 014 (T013, T021): the admission decision on one instance (no peers). */
class ThrottleDecisionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID KEY_B = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    private static ThrottleCaps keyCaps(Map<UUID, Integer> keys) {
        return new ThrottleCaps(keys, Map.of(), OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
    }

    private static Throttle throttle() {
        return new Throttle(1_000, 1_000);
    }

    // ---- US1: the key level ----

    @Test
    void keyCapAdmitsThenRefuses() {
        Throttle t = throttle();
        ThrottleCaps caps = keyCaps(Map.of(KEY, 5));
        for (int i = 0; i < 5; i++) {
            ThrottleDecision d = t.check(KEY, ORG, caps, NOW + i);
            assertTrue(d.admitted(), "request " + (i + 1));
            assertTrue(d.limited());
            assertEquals(Scope.KEY, d.scope());
            assertEquals(5, d.limit());
            assertEquals(4 - i, d.remaining());
            assertEquals(61, d.resetSeconds());
        }
        ThrottleDecision refused = t.check(KEY, ORG, caps, NOW + 10);
        assertFalse(refused.admitted());
        assertEquals(Scope.KEY, refused.scope());
        assertEquals(Reason.CAP, refused.reason());
        assertEquals(5, refused.limit());
        assertEquals(0, refused.remaining());
        assertEquals(61, refused.retryAfterSeconds(), "the bucket of NOW leaves the window 61 s later");
        assertTrue(t.check(KEY, ORG, caps, NOW + 61_000).admitted(), "after the advised wait");
    }

    @Test
    void aRefusalCountsNothing() {
        Throttle t = throttle();
        ThrottleCaps caps = keyCaps(Map.of(KEY, 1));
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        for (int i = 0; i < 10; i++) {
            assertFalse(t.check(KEY, ORG, caps, NOW + 1_000L * i).admitted());
        }
        assertEquals(1, t.window(Throttle.KEY, KEY).count(NOW / 1000 + 9));
        assertTrue(t.check(KEY, ORG, caps, NOW + 61_000).admitted(), "the refused requests did not extend the wait");
    }

    @Test
    void keysOfDifferentOrganisationsAreIndependent() {
        Throttle t = throttle();
        ThrottleCaps caps = keyCaps(Map.of(KEY, 1, KEY_B, 1));
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        assertFalse(t.check(KEY, ORG, caps, NOW).admitted());
        assertTrue(t.check(KEY_B, ORG_B, caps, NOW).admitted());
    }

    @Test
    void noCapAtAnyLevelIsUnlimited() {
        Throttle t = throttle();
        for (int i = 0; i < 100; i++) {
            ThrottleDecision d = t.check(KEY, ORG, ThrottleCaps.EMPTY, NOW);
            assertTrue(d.admitted());
            assertFalse(d.limited());
        }
        assertEquals(0, t.trackedSubjects(), "nothing is counted without a cap");
    }

    @Test
    void theDefaultKeyCapApplies() {
        Throttle t = throttle();
        ThrottleCaps caps = new ThrottleCaps(Map.of(), Map.of(), OptionalInt.of(2), OptionalInt.empty(), Instant.EPOCH);
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        assertFalse(t.check(KEY, ORG, caps, NOW).admitted());
    }

    // ---- US2: the organisation level ----

    private static ThrottleCaps caps(Map<UUID, Integer> keys, Map<UUID, Integer> orgs) {
        return new ThrottleCaps(keys, orgs, OptionalInt.empty(), OptionalInt.empty(), Instant.EPOCH);
    }

    @Test
    void organisationCapCountsEveryKey() {
        Throttle t = throttle();
        ThrottleCaps caps = caps(Map.of(KEY, 5, KEY_B, 5), Map.of(ORG, 6));
        int admitted = 0;
        ThrottleDecision last = null;
        for (int i = 0; i < 4; i++) {
            for (UUID k : new UUID[]{KEY, KEY_B}) {
                ThrottleDecision d = t.check(k, ORG, caps, NOW);
                if (d.admitted()) {
                    admitted++;
                } else {
                    last = d;
                }
            }
        }
        assertEquals(6, admitted);
        assertEquals(Scope.ORGANISATION, last.scope());
        assertEquals(Reason.CAP, last.reason());
        assertEquals(6, last.limit());
    }

    /** US2 AC3: a key-cap refusal uses none of the organisation's allowance. */
    @Test
    void aKeyRefusalDoesNotConsumeTheOrganisation() {
        Throttle t = throttle();
        ThrottleCaps caps = caps(Map.of(KEY, 1), Map.of(ORG, 3));
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        for (int i = 0; i < 5; i++) {
            ThrottleDecision d = t.check(KEY, ORG, caps, NOW);
            assertFalse(d.admitted());
            assertEquals(Scope.KEY, d.scope());
        }
        assertTrue(t.check(KEY_B, ORG, caps, NOW).admitted());
        assertTrue(t.check(KEY_B, ORG, caps, NOW).admitted());
        assertFalse(t.check(KEY_B, ORG, caps, NOW).admitted(), "exactly the organisation's 3");
    }

    /** Edge Cases: both caps reached → the key is reported, with the longer of the two waits. */
    @Test
    void bothLevelsRefuseReportsTheKeyWithTheLongerWait() {
        Throttle t = throttle();
        ThrottleCaps caps = caps(Map.of(KEY, 2, KEY_B, 10), Map.of(ORG, 3));
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());            // key leaves at +61 s
        assertTrue(t.check(KEY_B, ORG, caps, NOW + 30_000).admitted());  // organisation: 2
        assertTrue(t.check(KEY, ORG, caps, NOW + 40_000).admitted());   // key 2/2 (leaves +61, +101); org 3/3
        ThrottleDecision d = t.check(KEY, ORG, caps, NOW + 41_000);
        assertFalse(d.admitted());
        assertEquals(Scope.KEY, d.scope());
        // key: one of 2 must leave (T at +61 → 20 s from +41); org: one of 3 must leave (T at +61 → 20 s). Move the
        // key's first request later to make the organisation's wait the shorter one:
        Throttle t2 = throttle();
        assertTrue(t2.check(KEY_B, ORG, caps, NOW).admitted());          // org bucket at +0 (leaves +61)
        assertTrue(t2.check(KEY, ORG, caps, NOW + 10_000).admitted());   // key bucket at +10 (leaves +71)
        assertTrue(t2.check(KEY, ORG, caps, NOW + 20_000).admitted());   // key 2/2, org 3/3
        ThrottleDecision both = t2.check(KEY, ORG, caps, NOW + 30_000);
        assertFalse(both.admitted());
        assertEquals(Scope.KEY, both.scope());
        assertEquals(41, both.retryAfterSeconds(), "max(key 41 s, organisation 31 s)");
    }

    /** FR-010a: the headers show the level with the least remaining; the key on a tie. */
    @Test
    void headersShowTheTighterLevel() {
        Throttle t = throttle();
        ThrottleCaps caps = caps(Map.of(KEY, 10, KEY_B, 10), Map.of(ORG, 4));
        ThrottleDecision first = t.check(KEY, ORG, caps, NOW);
        assertEquals(Scope.ORGANISATION, first.scope());
        assertEquals(4, first.limit());
        assertEquals(3, first.remaining());
        Throttle tie = throttle();
        ThrottleDecision d = tie.check(KEY, ORG, caps(Map.of(KEY, 4), Map.of(ORG, 4)), NOW);
        assertEquals(Scope.KEY, d.scope(), "the key on a tie");
    }

    /** US2 AC4: no organisation cap of its own → the default organisation cap applies. */
    @Test
    void theDefaultOrganisationCapApplies() {
        Throttle t = throttle();
        ThrottleCaps caps = new ThrottleCaps(Map.of(), Map.of(), OptionalInt.empty(), OptionalInt.of(2), Instant.EPOCH);
        assertTrue(t.check(KEY, ORG, caps, NOW).admitted());
        assertTrue(t.check(KEY_B, ORG, caps, NOW).admitted());
        ThrottleDecision d = t.check(KEY, ORG, caps, NOW);
        assertFalse(d.admitted());
        assertEquals(Scope.ORGANISATION, d.scope());
    }
}
