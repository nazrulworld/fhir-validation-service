package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context keys with one or two IG packages (spec 012 FR-007, data-model §1).
 */
class SnapshotKeyTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    @Test
    void members() {
        assertEquals(List.of(), SnapshotKey.base(R4).members());
        assertEquals(List.of("a#1"), SnapshotKey.of(R4, "a#1").members());
        assertEquals(List.of("a#1", "b#2"), SnapshotKey.of(R4, "a#1|b#2").members());
        assertEquals(List.of("b#2", "a#1"), SnapshotKey.of(R4, "b#2|a#1").members(), "key order is kept");
    }

    @Test
    void combined() {
        assertFalse(SnapshotKey.base(R4).isCombined());
        assertFalse(SnapshotKey.of(R4, "a#1").isCombined());
        assertTrue(SnapshotKey.of(R4, "a#1|b#2").isCombined());
        assertEquals(SnapshotKey.of(R4, "a#1|b#2"), SnapshotKey.combined(R4, "a#1", "b#2"));
    }

    @Test
    void labelIsTheKey() {
        assertEquals("a#1|b#2", SnapshotKey.of(R4, "a#1|b#2").contextLabel());
        assertEquals("a#1", SnapshotKey.of(R4, "a#1").contextLabel());
        assertEquals("base", SnapshotKey.base(R4).contextLabel());
    }

    @Test
    void rejectsMalformedKeys() {
        for (String bad : List.of("a#1|", "|b#2", "a#1|b#2|c#3", "a#1|a#2", "a#1|a#1", "a#1|b", "a|b#2", "#1|b#2",
                "a#1||b#2", "a#1|b#")) {
            assertThrows(IllegalArgumentException.class, () -> SnapshotKey.of(R4, bad), bad);
        }
    }

    @Test
    void keyIsAtMost256Characters() {
        String a = "a".repeat(120) + "#1.0.0";
        String b256 = "b".repeat(256 - a.length() - 1 - 6) + "#2.0.0";
        assertEquals(256, (a + "|" + b256).length());
        assertDoesNotThrow(() -> SnapshotKey.of(R4, a + "|" + b256));
        assertThrows(IllegalArgumentException.class, () -> SnapshotKey.of(R4, a + "|b" + b256));
    }

    @Test
    void membersOfAString() {
        assertEquals(List.of("a#1", "b#2"), SnapshotKey.membersOf("a#1|b#2"));
        assertEquals(List.of("a#1"), SnapshotKey.membersOf("a#1"));
        assertEquals(List.of(), SnapshotKey.membersOf(null));
    }
}
