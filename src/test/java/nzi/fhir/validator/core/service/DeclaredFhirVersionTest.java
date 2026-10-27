package nzi.fhir.validator.core.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeclaredFhirVersionTest {

    @Test
    void releasesAreRecognisedByMajorAndMinor() {
        assertEquals(Optional.of(SupportedFhirVersion.STU3), SupportedFhirVersion.fromDeclaredVersion("3.0.2"));
        assertEquals(Optional.of(SupportedFhirVersion.R4), SupportedFhirVersion.fromDeclaredVersion("4.0.1"));
        assertEquals(Optional.of(SupportedFhirVersion.R4), SupportedFhirVersion.fromDeclaredVersion("4.0"));
        assertEquals(Optional.of(SupportedFhirVersion.R4B), SupportedFhirVersion.fromDeclaredVersion("4.3.0"));
        assertEquals(Optional.of(SupportedFhirVersion.R5), SupportedFhirVersion.fromDeclaredVersion("5.0.0-ballot"));
        assertEquals(Optional.empty(), SupportedFhirVersion.fromDeclaredVersion("1.0.2"));
        assertEquals(Optional.empty(), SupportedFhirVersion.fromDeclaredVersion(null));
    }

    @Test
    void declaredVersionsComeFromTheListOrTheSingleField() {
        assertEquals(Set.of(SupportedFhirVersion.R4, SupportedFhirVersion.R5), IgPackageService.declaredFhirVersions(
                new JsonObject().put("fhirVersions", new JsonArray().add("4.0.1").add("5.0.0"))));
        assertEquals(Set.of(SupportedFhirVersion.R4B),
                IgPackageService.declaredFhirVersions(new JsonObject().put("fhirVersion", "4.3.0")));
        assertTrue(IgPackageService.declaredFhirVersions(new JsonObject()).isEmpty(), "nothing declared: accepted");
        assertTrue(IgPackageService.declaredFhirVersions(null).isEmpty());
    }
}
