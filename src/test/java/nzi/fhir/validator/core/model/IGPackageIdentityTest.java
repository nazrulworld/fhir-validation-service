package nzi.fhir.validator.core.model;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.model.IGPackageIdentity;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirContextLoader;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Md Nazrul Islam
 */
public class IGPackageIdentityTest {

    @Test
    public void testIGPackageIdentity() {
        // with the default FHIR version
        IGPackageIdentity igPackageIdentity = new IGPackageIdentity("hl7.fhir.us.core", "7.0.0");
        assertEquals(SupportedFhirVersion.getDefault(), igPackageIdentity.getFhirVersion());
        assertEquals("classpath:ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz", igPackageIdentity.asClassPath());

        IGPackageIdentity igPackageIdentity2 = new IGPackageIdentity("hl7.fhir.us.core", "7.0.0", SupportedFhirVersion.getDefault());
        assertEquals(igPackageIdentity, igPackageIdentity2);
        assertEquals(igPackageIdentity.hashCode(), igPackageIdentity2.hashCode());
        assertEquals("hl7.fhir.us.core_r4_7.0.0", igPackageIdentity2.asId());

        JsonObject igJson1 = igPackageIdentity.asJsonObject();
        JsonObject igJson2 = igPackageIdentity2.asJsonObject();
        assertEquals(IGPackageIdentity.fromJson(igJson1), IGPackageIdentity.fromJson(igJson2.encode()));
        assertEquals(igJson1.hashCode(), igJson2.hashCode());
        FhirContextLoader loader = FhirContextLoader.getInstance();
        NpmPackageValidationSupport support = new NpmPackageValidationSupport(loader.getContext(SupportedFhirVersion.R4));
        try {
            support.loadPackageFromClasspath(igPackageIdentity2.asClassPath());
            StructureDefinition carePlanProfile = (StructureDefinition)support.fetchStructureDefinition("http://hl7.org/fhir/us/core/StructureDefinition/us-core-careplan");
            assertNotNull(carePlanProfile);
            assertNull(support.fetchStructureDefinition(carePlanProfile.getBaseDefinition()));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        IGPackageIdentity igPackageIdentity3 = IGPackageIdentity.createIGPackageIdentityForCorePackage(FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4));

    }
}
