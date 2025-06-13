package nzi.fhir.validator.model;

import nzi.fhir.validator.web.enums.SupportedFhirVersion;

import java.util.Objects;

/**
 * @author Md Nazrul Islam
 */
public class ValidatorIdentity {
    public static final String DEFAULT_ID = "standard_validator";
    private final String id;
    private final SupportedFhirVersion fhirVersion;

    public ValidatorIdentity(String id, SupportedFhirVersion fhirVersion) {
        this.id = id;
        this.fhirVersion = fhirVersion;
    }
    public static ValidatorIdentity createFromFhirVersion(SupportedFhirVersion fhirVersion){
        return new ValidatorIdentity(DEFAULT_ID, fhirVersion);
    }
    public String getId() {
        return id;
    }
    public SupportedFhirVersion getFhirVersion() {
        return fhirVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ValidatorIdentity that = (ValidatorIdentity) o;
        return fhirVersion == that.fhirVersion && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        String uniqueId = fhirVersion.name() + id;
        return Objects.hash(uniqueId);
    }
}
