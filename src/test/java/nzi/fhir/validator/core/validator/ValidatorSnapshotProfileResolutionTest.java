package nzi.fhir.validator.core.validator;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The profile-resolution rules of {@link ValidatorSnapshot#validate} on an in-memory snapshot, without a database
 * (spec 003, FR-003 to FR-008). {@code ProfileResolutionIT} covers the same rules end to end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidatorSnapshotProfileResolutionTest {
    private static final String BASE = "http://example.org/fhir/StructureDefinition/";
    private static final String P = BASE + "unit-p";
    private static final String DUP = BASE + "unit-dup";
    private static final String UNKNOWN = BASE + "unit-unknown";

    private ValidatorSnapshot snapshot;

    private static NpmPackage pkg(String name, JsonObject... profiles) throws IOException {
        return NpmPackage.fromPackage(new ByteArrayInputStream(
                TestPackages.npmPackage(name, "1.0.0", Map.of(), List.of(profiles))));
    }

    @BeforeAll
    void build() throws IOException {
        // Resolved sets are sorted, so dup.b is added last and wins the unversioned DUP.
        List<NpmPackage> packages = List.of(
                pkg("dup.a", TestPackages.requiredPatientElements(DUP, "birthDate").put("version", "1.0.0").put("id", "dupa")),
                pkg("dup.b", TestPackages.requiredPatientElements(DUP, "gender").put("version", "2.0.0").put("id", "dupb")),
                pkg("unit.main", TestPackages.requiredPatientElements(P, "birthDate").put("version", "1.0.0")));
        snapshot = SnapshotBuilder.assemble(SupportedFhirVersion.R4, 1,
                List.of("dup.a#1.0.0", "dup.b#1.0.0", "unit.main#1.0.0"), packages);
    }

    private ValidatorSnapshot.Outcome validate(String body, String... profiles) {
        return snapshot.validate(body, SupportedContentType.JSON, List.of(profiles));
    }

    private static void assertUnknown(Runnable call, String... unresolved) {
        ProblemException e = assertThrows(ProblemException.class, call::run);
        assertEquals(ProblemException.TYPE_UNKNOWN_PROFILE, e.type());
        assertEquals(new JsonArray(List.of(unresolved)), e.extensions().getJsonArray("unresolvedProfiles"));
    }

    @Test
    void knownProfileIsAppliedAndListed() {
        ValidatorSnapshot.Outcome o = validate(TestPackages.minimalPatient(), P);
        assertTrue(TestPackages.hasError(o, "birthDate"));
        assertEquals(List.of(P), o.appliedProfiles());
    }

    @Test
    void anyUnknownProfileRejectsTheRequest() {
        assertUnknown(() -> validate(TestPackages.minimalPatient(), P, UNKNOWN), UNKNOWN);
        assertUnknown(() -> validate(TestPackages.minimalPatient(), UNKNOWN), UNKNOWN);
    }

    @Test
    void coreProfilesResolve() {
        String core = "http://hl7.org/fhir/StructureDefinition/Patient";
        assertEquals(List.of(core), validate(TestPackages.minimalPatient(), core).appliedProfiles());
    }

    @Test
    void onlyExactCanonicalsResolve() {
        assertUnknown(() -> validate(TestPackages.minimalPatient(), "unit-p", "StructureDefinition/unit-p"),
                "unit-p", "StructureDefinition/unit-p");
    }

    @Test
    void versionSuffixSelectsThatVersion() {
        assertTrue(TestPackages.hasError(validate(TestPackages.minimalPatient(), P + "|1.0.0"), "birthDate"));
        assertUnknown(() -> validate(TestPackages.minimalPatient(), P + "|9.9.9"), P + "|9.9.9");
    }

    @Test
    void duplicateCanonicalIsDeterministic() {
        ValidatorSnapshot.Outcome unversioned = validate(TestPackages.minimalPatient(), DUP);
        assertTrue(TestPackages.hasError(unversioned, "gender") && !TestPackages.hasError(unversioned, "birthDate"),
                unversioned.result().getMessages().toString());
        assertTrue(TestPackages.hasError(validate(TestPackages.minimalPatient(), DUP + "|1.0.0"), "birthDate"));
        assertTrue(TestPackages.hasError(validate(TestPackages.minimalPatient(), DUP + "|2.0.0"), "gender"));
    }

    @Test
    void unknownMetaProfileIsAnErrorButNeverListed() {
        String body = new JsonObject().put("resourceType", "Patient").put("active", true)
                .put("meta", new JsonObject().put("profile", new JsonArray().add(UNKNOWN).add(P))).encode();
        ValidatorSnapshot.Outcome o = validate(body, P);
        assertFalse(o.result().isSuccessful());
        assertEquals(List.of(P), o.appliedProfiles(), "requested once, unknown declared one never listed");
    }
}
