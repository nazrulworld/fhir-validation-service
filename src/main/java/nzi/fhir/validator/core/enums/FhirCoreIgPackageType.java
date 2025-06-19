package nzi.fhir.validator.core.enums;

import ca.uhn.fhir.context.FhirContext;

import java.util.ArrayList;

public enum FhirCoreIgPackageType {
    STU3("hl7.fhir.r3.core", "hl7.fhir.r3.core-3.0.2.tgz", "3.0.2"),
    R4("hl7.fhir.r4.core", "hl7.fhir.r4.core-4.0.1.tgz", "4.0.1"),
    R4B("hl7.fhir.r4b.core", "hl7.fhir.r4b.core-4.3.0.tgz", "4.3.0"),
    R5("hl7.fhir.r5.core", "hl7.fhir.r5.core-5.0.0.tgz", "5.0.0");


    private final String name;
    private final String filename;
    private final String version;

    FhirCoreIgPackageType(String name, String filename, String version) {
        this.name = name;
        this.filename = filename;
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public String getFilename() {
        return filename;
    }

    public String getVersion() {
        return version;
    }
    public static FhirCoreIgPackageType fromFhirVersionName(String name){

        return switch (name) {
            case "R3" -> STU3;
            case "R4" -> R4;
            case "R4B" -> R4B;
            case "R5" -> R5;
            default -> throw new IllegalStateException("Unexpected value: " + name);
        };
    }

    public static ArrayList<String> getNameList(){
        ArrayList<String> nameList = new ArrayList<>();
        for (FhirCoreIgPackageType fhirCoreIgPackageType : FhirCoreIgPackageType.values()) {
            nameList.add(fhirCoreIgPackageType.getName());
        }
        return nameList;
    }
    public static FhirCoreIgPackageType fromFhirContext(FhirContext fhirContext){
        return switch (fhirContext.getVersion().getVersion().getFhirVersionString()) {
            case "3.0.0", "3.0.1", "3.0.2" -> STU3;
            case "4.0.0", "4.0.1" -> R4;
            case "4.3.0" -> R4B;
            case "5.0.0" -> R5;
            default -> throw new IllegalStateException("Unexpected value: " + fhirContext.getVersion().getVersion().getFhirVersionString());
        };
    }
}
