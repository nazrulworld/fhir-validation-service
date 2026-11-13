package nzi.fhir.validator.core.validator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 (T028, FR-014): reactivation only matches contexts that are inactive because their FHIR version wasn't
 * served; a context made inactive for any other (future) reason is never reactivated by it.
 */
class ContextActivationTest {

    @Test
    void reactivationMatchesOnlyTheFhirVersionReason() {
        String sql = ContextActivation.REACTIVATE_SQL;
        assertTrue(sql.contains("s.inactive_reason = 'fhir-version-disabled'"), sql);
        assertTrue(sql.contains("s.fhir_version = ANY($1)"), sql);
        assertFalse(sql.contains("NOT s.is_active"), "must not match every inactive context: " + sql);
    }

    @Test
    void deactivationMatchesOnlyActiveContextsOfUnservedVersions() {
        String sql = ContextActivation.DEACTIVATE_SQL;
        assertTrue(sql.contains("s.is_active AND NOT (s.fhir_version = ANY($1))"), sql);
        assertTrue(sql.contains("inactive_reason = 'fhir-version-disabled'"), sql);
    }
}
