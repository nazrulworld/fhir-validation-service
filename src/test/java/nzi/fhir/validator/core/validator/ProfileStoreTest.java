package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirContextLoader;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {

    private static StructureDefinition sd(String url, String name) {
        StructureDefinition sd = new StructureDefinition();
        sd.setUrl(url);
        sd.setName(name);
        return sd;
    }

    @Test
    void urlsDifferingOnlyInPunctuationAreDistinct() {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);
        ProfileStore store = new ProfileStore(SupportedFhirVersion.R4, ctx);
        store.put("http://ex.org/a-b", sd("http://ex.org/a-b", "Dash"));
        store.put("http://ex.org/a.b", sd("http://ex.org/a.b", "Dot"));
        store.put("http://ex.org/a_b", sd("http://ex.org/a_b", "Underscore"));

        assertEquals("Dash", ((StructureDefinition) store.get("http://ex.org/a-b")).getName());
        assertEquals("Dot", ((StructureDefinition) store.get("http://ex.org/a.b")).getName());
        assertEquals("Underscore", ((StructureDefinition) store.get("http://ex.org/a_b")).getName());
        assertEquals(3, store.size());
        assertNull(store.get("http://ex.org/a|b"));
    }

    @Test
    void versionedCanonicalFallsBackToUnversionedEntry() {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);
        ProfileStore store = new ProfileStore(SupportedFhirVersion.R4, ctx);
        store.put("http://ex.org/p", sd("http://ex.org/p", "P"));
        assertNotNull(store.get("http://ex.org/p|1.0.0"));
    }
}
