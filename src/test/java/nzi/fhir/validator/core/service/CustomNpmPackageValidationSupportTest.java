package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.SharedPackageDefinitions;
import nzi.fhir.validator.core.validator.SnapshotPregenerator;
import nzi.fhir.validator.testsupport.TestPackages;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 FR-017, FR-020, FR-021, research R7: two contexts built from one shared package hold the same objects for
 * definitions complete as shipped; a profile a context completes, or that stays incomplete, is private to it; and the
 * last package added still wins for a duplicate canonical URL.
 */
class CustomNpmPackageValidationSupportTest {
    private static final FhirContext CTX = FhirContext.forR4Cached();
    private static final String COMPLETE = "http://example.org/fhir/StructureDefinition/complete-patient";
    private static final String DIFF_ONLY = "http://example.org/fhir/StructureDefinition/diff-patient";
    private static final String NO_BASE = "http://example.org/fhir/StructureDefinition/no-base";
    private static final String VS = "http://example.org/fhir/ValueSet/vs";
    private static final byte[] HASH = {1, 2, 3};

    /** A profile that ships with its full form: core Patient's snapshot under a new URL. */
    private static JsonObject completeProfile() {
        StructureDefinition core = (StructureDefinition) new ca.uhn.fhir.context.support.DefaultProfileValidationSupport(CTX)
                .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient");
        StructureDefinition sd = core.copy();
        sd.setId("complete-patient");
        sd.setUrl(COMPLETE);
        sd.setName("CompletePatient");
        sd.setDerivation(StructureDefinition.TypeDerivationRule.CONSTRAINT);
        sd.setBaseDefinition("http://hl7.org/fhir/StructureDefinition/Patient");
        org.hl7.fhir.r4.model.ElementDefinition root = new org.hl7.fhir.r4.model.ElementDefinition().setPath("Patient");
        root.setId("Patient");
        sd.setDifferential(new StructureDefinition.StructureDefinitionDifferentialComponent().addElement(root));
        return new JsonObject(CTX.newJsonParser().encodeResourceToString(sd));
    }

    private static JsonObject noBaseProfile() {
        JsonObject sd = TestPackages.requiredPatientElements(NO_BASE, "birthDate");
        sd.put("id", "no-base");
        sd.put("baseDefinition", "http://example.org/fhir/StructureDefinition/does-not-exist");
        return sd;
    }

    private static JsonObject valueSet() {
        return new JsonObject().put("resourceType", "ValueSet").put("id", "vs").put("url", VS).put("status", "active")
                .put("compose", new JsonObject().put("include", new JsonArray().add(new JsonObject()
                        .put("system", "http://loinc.org"))));
    }

    private static NpmPackage pkg(String name, JsonObject... resources) throws Exception {
        return NpmPackage.fromPackage(new ByteArrayInputStream(
                TestPackages.npmPackage(name, "1.0.0", Map.of(), List.of(resources))));
    }

    private static CustomNpmPackageValidationSupport support(NpmPackage pkg, SharedPackageDefinitions shared) {
        return new CustomNpmPackageValidationSupport(CTX, List.of(pkg), Map.of(pkg.name() + "#" + pkg.version(), HASH),
                shared, SupportedFhirVersion.R4);
    }

    @Test
    void completeDefinitionsAreSharedAndCompletedOnesArePrivate() throws Exception {
        JsonObject diff = TestPackages.requiredPatientElements(DIFF_ONLY, "birthDate");
        diff.put("id", "diff-patient");
        NpmPackage pkg = pkg("shared.pkg", completeProfile(), diff, noBaseProfile(), valueSet());
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        CustomNpmPackageValidationSupport x = support(pkg, shared);
        CustomNpmPackageValidationSupport y = support(pkg, shared);
        assertEquals(1, shared.entries(SupportedFhirVersion.R4), "parsed once");

        assertSame(x.fetchStructureDefinition(COMPLETE), y.fetchStructureDefinition(COMPLETE), "complete as shipped: shared");
        assertSame(x.fetchValueSet(VS), y.fetchValueSet(VS), "other resource types are shared as parsed");

        IBaseResource xDiff = x.fetchStructureDefinition(DIFF_ONLY);
        IBaseResource yDiff = y.fetchStructureDefinition(DIFF_ONLY);
        assertNotSame(xDiff, yDiff, "a profile each context completed is its own");
        assertFalse(SnapshotPregenerator.needsSnapshot(CTX, xDiff));
        IBaseResource sharedDiff = x.getSharedPackages().get(0).resources().stream()
                .filter(r -> r instanceof StructureDefinition sd && DIFF_ONLY.equals(sd.getUrl())).findFirst().orElseThrow();
        assertTrue(SnapshotPregenerator.needsSnapshot(CTX, sharedDiff), "the shared original was not completed in place");

        IBaseResource xNoBase = x.fetchStructureDefinition(NO_BASE);
        IBaseResource yNoBase = y.fetchStructureDefinition(NO_BASE);
        assertNotSame(xNoBase, yNoBase, "a profile that stays incomplete is a private copy (FR-020)");
        IBaseResource sharedNoBase = x.getSharedPackages().get(0).resources().stream()
                .filter(r -> r instanceof StructureDefinition sd && NO_BASE.equals(sd.getUrl())).findFirst().orElseThrow();
        assertNotSame(sharedNoBase, xNoBase);
        assertNotSame(sharedNoBase, yNoBase);
    }

    @Test
    void theLastPackageAddedStillWinsForADuplicateCanonical() throws Exception {
        JsonObject first = TestPackages.requiredPatientElements(DIFF_ONLY, "birthDate");
        first.put("id", "first").put("publisher", "first");
        JsonObject second = TestPackages.requiredPatientElements(DIFF_ONLY, "gender");
        second.put("id", "second").put("publisher", "second");
        NpmPackage a = pkg("a.pkg", first);
        NpmPackage b = pkg("b.pkg", second);
        Map<String, byte[]> hashes = Map.of("a.pkg#1.0.0", HASH, "b.pkg#1.0.0", new byte[]{9});
        CustomNpmPackageValidationSupport sharedSupport = new CustomNpmPackageValidationSupport(CTX, List.of(a, b), hashes,
                new SharedPackageDefinitions(), SupportedFhirVersion.R4);
        CustomNpmPackageValidationSupport privateSupport = new CustomNpmPackageValidationSupport(CTX, List.of(a, b));
        assertEquals(((StructureDefinition) privateSupport.fetchStructureDefinition(DIFF_ONLY)).getPublisher(),
                ((StructureDefinition) sharedSupport.fetchStructureDefinition(DIFF_ONLY)).getPublisher(),
                "the same definition wins with and without sharing (FR-021)");
        assertEquals("second", ((StructureDefinition) sharedSupport.fetchStructureDefinition(DIFF_ONLY)).getPublisher());
    }

    @Test
    void aPackageWithoutAHashIsNotShared() throws Exception {
        NpmPackage pkg = pkg("nohash.pkg", completeProfile());
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        CustomNpmPackageValidationSupport x = new CustomNpmPackageValidationSupport(CTX, List.of(pkg), Map.of(), shared,
                SupportedFhirVersion.R4);
        CustomNpmPackageValidationSupport y = new CustomNpmPackageValidationSupport(CTX, List.of(pkg), Map.of(), shared,
                SupportedFhirVersion.R4);
        assertEquals(0, shared.entries(SupportedFhirVersion.R4), "FR-017: no content hash, no sharing");
        assertNotSame(x.fetchStructureDefinition(COMPLETE), y.fetchStructureDefinition(COMPLETE));
    }
}
