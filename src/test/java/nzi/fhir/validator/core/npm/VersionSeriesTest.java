package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version series such as {@code 1.2.x} (spec 006 FR-001 to FR-005, data-model §1).
 */
class VersionSeriesTest {

    private static final String SERIES_MESSAGE = "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)";

    @ParameterizedTest
    @ValueSource(strings = {"1.2.x", "1.2.X", "1.x", "1.x.x", "1.x.3", "x.x.x", "x"})
    void detectsSeries(String value) {
        assertTrue(VersionSeries.isSeries(value), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3", "1.2", "latest", "current", "1.2.3-ballot", "1.2.x-ballot", "xx", "1.2.xy"})
    void detectsNonSeries(String value) {
        assertFalse(VersionSeries.isSeries(value), value);
    }

    @Test
    void nullIsNotASeries() {
        assertFalse(VersionSeries.isSeries(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.x", "1.x.x", "1.2.x", "1.2.X", "10.20.x"})
    void parsesValidSeries(String value) {
        assertEquals(value, VersionSeries.parse(value).raw());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "x.x.x", "1.x.3", "1.2.x.y", "1.2.3.x", "1.2.x.x", "1.X.2", "-1.x"})
    void rejectsMalformedSeries(String value) {
        ProblemException e = assertThrows(ProblemException.class, () -> VersionSeries.parse(value), value);
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertEquals(SERIES_MESSAGE, e.detail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2", "1.2.0", "1.2.7", "1.2.7-ballot", "1.2.7+build"})
    void minorSeriesMatches(String version) {
        assertTrue(VersionSeries.parse("1.2.x").matches(version), version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20.0", "1.21.3", "1.3.0", "1.1.9", "current", "2020Sep", "1"})
    void minorSeriesDoesNotMatch(String version) {
        assertFalse(VersionSeries.parse("1.2.x").matches(version), version);
    }

    @Test
    void majorSeriesMatching() {
        VersionSeries major = VersionSeries.parse("1.x");
        assertTrue(major.matches("1.0.0"));
        assertTrue(major.matches("1.9.9"));
        assertTrue(major.matches("1"));
        assertFalse(major.matches("2.0.0"));
        assertFalse(major.matches(null));
    }

    @Test
    void missingPartsCountAsZero() {
        assertFalse(VersionSeries.parse("1.2.x").matches("1"));
        assertTrue(VersionSeries.parse("1.2.x").matches("1.2"));
    }

    @Test
    void highestFollowsDataModelExamples() {
        assertEquals(Optional.of("1.2.5"), VersionSeries.parse("1.2.x").highest(List.of("1.2.0", "1.2.5", "1.20.1")));
        assertEquals(Optional.of("1.3.0"), VersionSeries.parse("1.x").highest(List.of("1.2.3", "1.3.0", "2.0.0")));
        assertEquals(Optional.of("1.3.0"), VersionSeries.parse("1.x.x").highest(List.of("1.2.3", "1.3.0", "2.0.0")));
        assertEquals(Optional.of("1.2"), VersionSeries.parse("1.2.x").highest(List.of("1.2", "1.1.9")));
        assertEquals(Optional.of("1.2.0"), VersionSeries.parse("1.2.x").highest(List.of("1.2.0", "1.2.1-ballot")));
        assertEquals(Optional.of("1.2.1-ballot"), VersionSeries.parse("1.2.x").highest(List.of("1.2.1-ballot")));
        assertEquals(Optional.empty(), VersionSeries.parse("1.2.x").highest(List.of("current", "1.3.0")));
        assertEquals(Optional.empty(), VersionSeries.parse("1.2.x").highest(List.of()));
    }

    @Test
    void highestIsDeterministic() {
        List<String> candidates = new ArrayList<>(List.of("1.2.0", "1.2.5", "1.2.5-ballot", "1.20.1", "1.2.4", "current"));
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(candidates, random);
            assertEquals(Optional.of("1.2.5"), VersionSeries.parse("1.2.x").highest(candidates));
        }
    }

    @Test
    void rawIsUnchanged() {
        assertEquals("1.2.X", VersionSeries.parse("1.2.X").raw());
    }
}
