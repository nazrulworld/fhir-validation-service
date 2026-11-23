package nzi.fhir.validator.core.throttle;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 014 (T009, FR-006): which cap applies to a key and to an organisation. */
class ThrottleCapsTest {
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID OTHER_KEY = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    @Test
    void ownCapElseDefaultElseNone() {
        ThrottleCaps caps = new ThrottleCaps(Map.of(KEY, 5), Map.of(ORG, 7), OptionalInt.of(600), OptionalInt.of(1200),
                java.time.Instant.EPOCH);
        assertEquals(OptionalInt.of(5), caps.keyCap(KEY));
        assertEquals(OptionalInt.of(600), caps.keyCap(OTHER_KEY));
        assertEquals(OptionalInt.of(7), caps.orgCap(ORG));
        assertEquals(OptionalInt.of(1200), caps.orgCap(OTHER_ORG));
    }

    @Test
    void ownCapWinsWhetherLowerOrHigher() {
        ThrottleCaps caps = new ThrottleCaps(Map.of(KEY, 5000), Map.of(ORG, 1), OptionalInt.of(600), OptionalInt.of(1200),
                java.time.Instant.EPOCH);
        assertEquals(OptionalInt.of(5000), caps.keyCap(KEY));
        assertEquals(OptionalInt.of(1), caps.orgCap(ORG));
    }

    @Test
    void noDefaultMeansNotLimitedAtThatLevel() {
        ThrottleCaps caps = new ThrottleCaps(Map.of(KEY, 5), Map.of(), OptionalInt.empty(), OptionalInt.empty(),
                java.time.Instant.EPOCH);
        assertEquals(OptionalInt.of(5), caps.keyCap(KEY));
        assertEquals(OptionalInt.empty(), caps.keyCap(OTHER_KEY));
        assertEquals(OptionalInt.empty(), caps.orgCap(ORG));
        assertEquals(OptionalInt.empty(), ThrottleCaps.EMPTY.keyCap(KEY));
        assertEquals(OptionalInt.empty(), ThrottleCaps.EMPTY.orgCap(ORG));
    }
}
