package nzi.fhir.validator.core.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static nzi.fhir.validator.core.enums.SupportedFhirVersion.*;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 009 FR-001 to FR-003 (T007, research R1/R2): parsing {@code FV_ENABLED_FHIR_VERSIONS}. */
class ServedVersionsTest {

    @Test
    void trimsIgnoresCaseAndDuplicatesInDeclarationOrder() {
        ServedVersions v = ServedVersions.parse("r5, R4 ,R4");
        assertEquals(List.of(R4, R5), v.asList());
        assertTrue(v.contains(R4));
        assertFalse(v.contains(STU3));
        assertFalse(v.isAll());
        assertEquals("R4, R5", v.label());
    }

    @Test
    void unsetOrEmptyMeansAllSupportedVersions() {
        for (String raw : new String[]{null, "", "   ", " , ", ","}) {
            ServedVersions v = ServedVersions.parse(raw);
            assertTrue(v.isAll(), String.valueOf(raw));
            assertEquals(SupportedFhirVersion.enabled(), v.asList());
            assertSame(ServedVersions.ALL, v);
            assertFalse(v.isConfigured(), "the log says the variable is not set");
        }
        assertEquals("STU3, R4, R4B, R5", ServedVersions.ALL.label());
        assertFalse(ServedVersions.ALL.contains(R6), "R6 is known but never served");
    }

    @Test
    void allFourListedExplicitlyIsAll() {
        assertTrue(ServedVersions.parse("STU3,R4,R4B,R5").isAll());
        assertTrue(ServedVersions.parse("STU3,R4,R4B,R5").isConfigured());
        assertEquals(ServedVersions.ALL, ServedVersions.parse("STU3,R4,R4B,R5"), "equal sets are equal however they were set");
        assertTrue(ServedVersions.parse("R4").isConfigured());
    }

    @Test
    void unknownNameFailsNamingItAndListingAcceptedValues() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ServedVersions.parse("R4,X1"));
        assertEquals("FV_ENABLED_FHIR_VERSIONS: 'X1' is not a FHIR version this service serves "
                + "(accepted: STU3, R4, R4B, R5)", e.getMessage());
    }

    @Test
    void r6IsKnownButNotServed() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ServedVersions.parse("r6"));
        assertEquals("FV_ENABLED_FHIR_VERSIONS: 'R6' is known but not served by this release "
                + "(accepted: STU3, R4, R4B, R5)", e.getMessage());
    }

    @Test
    void findReturnsServedVersionsOnly() {
        ServedVersions v = ServedVersions.parse("R4");
        assertEquals(java.util.Optional.of(R4), v.find(" r4 "));
        assertTrue(v.find("R5").isEmpty());
        assertTrue(v.find("R9").isEmpty());
        assertTrue(v.find(null).isEmpty());
    }
}
