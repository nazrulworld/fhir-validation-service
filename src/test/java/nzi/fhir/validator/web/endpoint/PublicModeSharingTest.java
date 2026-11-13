package nzi.fhir.validator.web.endpoint;

import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.AclMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nzi.fhir.validator.web.endpoint.PublicModeSharing.FORCED_SHAREABLE_WARNING;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 009 FR-034 (T062). */
class PublicModeSharingTest {

    @Test
    void disabledModeForcesShareableWithAWarning() {
        PublicModeSharing sharing = new PublicModeSharing(AclMode.DISABLED);
        List<String> warnings = new ArrayList<>();
        assertTrue(sharing.apply(false, warnings));
        assertEquals(List.of(FORCED_SHAREABLE_WARNING), warnings);

        warnings.clear();
        assertTrue(sharing.apply(true, warnings), "private to shareable still works");
        assertEquals(Optional.of(true), sharing.apply(Optional.of(false), warnings));
        assertEquals(Optional.empty(), sharing.apply(Optional.empty(), new ArrayList<>()), "absent stays absent");
        assertEquals(List.of(FORCED_SHAREABLE_WARNING), warnings, "one warning, for the forced value only");
    }

    @Test
    void disabledModeKeepsTheRestOfAChange() {
        PublicModeSharing sharing = new PublicModeSharing(AclMode.DISABLED);
        List<String> warnings = new ArrayList<>();
        AccessChange change = AccessChange.of(Optional.of(false), Optional.of("acme"));
        AccessChange forced = sharing.apply(change, warnings);
        assertEquals(Optional.of(true), forced.shareable());
        assertEquals(Optional.of("acme"), forced.owner(), "a transfer still applies");
        assertNull(forced.invalid());
        assertEquals(List.of(FORCED_SHAREABLE_WARNING), warnings);

        AccessChange invalid = AccessChange.of(Optional.empty(), Optional.empty());
        assertSame(invalid, sharing.apply(invalid, warnings), "nothing to force: unchanged, still invalid");
        AccessChange share = AccessChange.of(Optional.of(true), Optional.empty());
        assertSame(share, sharing.apply(share, warnings));
    }

    @Test
    void enabledModeChangesNothing() {
        PublicModeSharing sharing = new PublicModeSharing(AclMode.ENABLED);
        List<String> warnings = new ArrayList<>();
        assertFalse(sharing.apply(false, warnings));
        assertEquals(Optional.of(false), sharing.apply(Optional.of(false), warnings));
        AccessChange change = AccessChange.of(Optional.of(false), Optional.empty());
        assertSame(change, sharing.apply(change, warnings));
        assertEquals(List.of(), warnings);
    }
}
