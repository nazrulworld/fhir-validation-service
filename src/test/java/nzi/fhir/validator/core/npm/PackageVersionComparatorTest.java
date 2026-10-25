package nzi.fhir.validator.core.npm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackageVersionComparatorTest {

    private static final PackageVersionComparator C = PackageVersionComparator.INSTANCE;

    @ParameterizedTest(name = "{0} > {1}")
    @CsvSource({
            "2.0.0, 1.5.0",
            "1.10.0, 1.9.0",
            "1.0.0, 1.0.0-ballot",
            "1.0.0-ballot2, 1.0.0-ballot1",
            "1.0.0-beta.11, 1.0.0-beta.2",
            "1.0.0-beta, 1.0.0-alpha",
            "1.0.0-alpha.1, 1.0.0-alpha",
            "1.0.1, 1.0.0",
            "4.0.1, 4.0.0",
            "0.0.1, current",
            "0.0.1, dev",
            "1.0.0-snapshot, cibuild",
            "2.0, 1.9.9",
    })
    void ordersGreaterFirst(String greater, String lesser) {
        assertTrue(C.compare(greater, lesser) > 0, greater + " should be > " + lesser);
        assertTrue(C.compare(lesser, greater) < 0, lesser + " should be < " + greater);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
            "1.0.0+build, 1.0.0",
            "1.0.0, 1.0.0",
            "1.0, 1.0.0",
    })
    void equalVersions(String a, String b) {
        assertEquals(0, C.compare(a, b));
    }

    @Test
    void neverThrowsOnOddInput() {
        for (String s : List.of("", "x", "1..2", "-", "1.0.0-", "v1", "current", "1.0.0-ballot-2023", "99999999999999999999.0")) {
            assertDoesNotThrow(() -> C.compare(s, "1.0.0"));
            assertDoesNotThrow(() -> C.compare("1.0.0", s));
        }
    }

    @Test
    void maxPicksHighestRelease() {
        assertEquals("2.0.0", PackageVersionComparator.max(List.of("1.5.0", "2.0.0", "2.1.0-ballot", "current")).orElseThrow());
        assertTrue(PackageVersionComparator.max(List.of()).isEmpty());
    }
}
