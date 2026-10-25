package nzi.fhir.validator.core.model;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link SupportedFhirVersion} enum.
 */
public class SupportedFhirVersionTest {

    @Test
    public void testIsValid_withValidVersions() {
        // Test all valid versions
        assertTrue(SupportedFhirVersion.isValid("R4"), "R4 should be valid");
        assertTrue(SupportedFhirVersion.isValid("R4B"), "R4B should be valid");
        assertTrue(SupportedFhirVersion.isValid("R5"), "R5 should be valid");
        
        // Test case insensitivity
        assertTrue(SupportedFhirVersion.isValid("r4"), "r4 (lowercase) should be valid");
        assertTrue(SupportedFhirVersion.isValid("r4b"), "r4b (lowercase) should be valid");
        assertTrue(SupportedFhirVersion.isValid("r5"), "r5 (lowercase) should be valid");
    }
    
    @Test
    public void testIsValid_withInvalidVersions() {
        // Test invalid versions
        assertFalse(SupportedFhirVersion.isValid("R3"), "R3 should be invalid");
        assertFalse(SupportedFhirVersion.isValid("R7"), "R6 should be invalid");
        assertFalse(SupportedFhirVersion.isValid(""), "Empty string should be invalid");
        assertFalse(SupportedFhirVersion.isValid(null), "Null should be invalid");
        assertFalse(SupportedFhirVersion.isValid("INVALID"), "Random string should be invalid");
    }
    
    @Test
    public void parseEnabledIsCaseInsensitiveAndOnlyAcceptsEnabledVersions() {
        assertEquals(java.util.Optional.of(SupportedFhirVersion.R4), SupportedFhirVersion.parseEnabled("r4"));
        assertEquals(java.util.Optional.of(SupportedFhirVersion.STU3), SupportedFhirVersion.parseEnabled("STU3"));
        assertEquals(java.util.Optional.of(SupportedFhirVersion.R4B), SupportedFhirVersion.parseEnabled("r4B"));
        assertTrue(SupportedFhirVersion.parseEnabled("R6").isEmpty(), "R6 exists but is not enabled");
        assertTrue(SupportedFhirVersion.parseEnabled("2b6c6a3e-1f1e-4a51-9d55-0a1b2c3d4e5f").isEmpty());
        assertTrue(SupportedFhirVersion.parseEnabled("").isEmpty());
        assertTrue(SupportedFhirVersion.parseEnabled(null).isEmpty());
    }

    @Test
    public void enabledVersionsAreStu3ToR5() {
        assertTrue(SupportedFhirVersion.STU3.isEnabled());
        assertTrue(SupportedFhirVersion.R4.isEnabled());
        assertTrue(SupportedFhirVersion.R4B.isEnabled());
        assertTrue(SupportedFhirVersion.R5.isEnabled());
        assertFalse(SupportedFhirVersion.R6.isEnabled());
        assertEquals(java.util.List.of(SupportedFhirVersion.STU3, SupportedFhirVersion.R4, SupportedFhirVersion.R4B, SupportedFhirVersion.R5),
                SupportedFhirVersion.enabled());
    }

    @Test
    public void testGetDefault() {
        // Test default version
        assertEquals(SupportedFhirVersion.R4, SupportedFhirVersion.getDefault(), 
                "Default version should be R4");
    }
    
    @Test
    public void testEnumValues() {
        // Test enum values
        SupportedFhirVersion[] values = SupportedFhirVersion.values();
        assertEquals(5, values.length, "There should be exactly 5 supported versions");
        
        // Verify the specific values
        assertEquals(SupportedFhirVersion.R4, values[1], "First value should be R4");
        assertEquals(SupportedFhirVersion.R4B, values[2], "Second value should be R4B");
        assertEquals(SupportedFhirVersion.R5, values[3], "Third value should be R5");
    }
}