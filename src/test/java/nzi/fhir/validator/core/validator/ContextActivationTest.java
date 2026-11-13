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

    /**
     * Code review of PR #72: validation sees only active contexts ({@code activeAccess}), while exclude and PATCH still
     * see every included one ({@code access}, FR-032).
     */
    @Test
    void validationSeesOnlyActiveContextsButAdministrationSeesAll() {
        java.util.List<String> included = java.util.List.of("a.ig#1.0.0", "b.ig#1.0.0");
        PinnedAccess access = PinnedAccess.of(included, java.util.Map.of(), "system");
        nzi.fhir.validator.core.service.ConfigRevisionRepository.ConfigRow row =
                new nzi.fhir.validator.core.service.ConfigRevisionRepository.ConfigRow(3, included, access, java.util.Map.of(),
                        java.util.Map.of("b.ig#1.0.0", "fhir-version-disabled"));
        assertEquals(java.util.Set.of("a.ig#1.0.0", "b.ig#1.0.0"), row.access().asMap().keySet());
        assertEquals(java.util.Set.of("a.ig#1.0.0"), row.activeAccess().asMap().keySet());
        assertEquals(java.util.List.of("a.ig#1.0.0"), row.active());
        assertEquals(java.util.Set.of("a.ig#1.0.0"), ConfigSync.resolveAll(row, included,
                nzi.fhir.validator.core.npm.PackageCatalogView.EMPTY, row.activeAccess()).keySet());
    }
}
