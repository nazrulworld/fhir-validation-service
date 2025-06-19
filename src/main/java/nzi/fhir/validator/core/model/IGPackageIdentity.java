package nzi.fhir.validator.core.model;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.FhirCoreIgPackageType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import java.io.File;

/**
 * @author Md Nazrul Islam
 */

public class IGPackageIdentity {
    public static final String IG_DEFAULT_PACKAGE_NAME = "org.hl7.fhir";
    private final String name;
    private final String version;
    private final SupportedFhirVersion fhirVersion;
    private static final String ARCHIVE_EXTENSION = ".tgz";
    private static final String PACKAGE_ROOT_DIRECTORY = "ig" + File.separator + "packages";


    public IGPackageIdentity(final String name, final String version) {

        if (name == null || version == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        this.name = name;
        this.version = version;
        this.fhirVersion = SupportedFhirVersion.getDefault();
    }
    public IGPackageIdentity(final String name, final String version, final SupportedFhirVersion fhirVersion) {

        if (name == null || version == null || fhirVersion == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        this.name = name;
        this.version = version;
        this.fhirVersion = fhirVersion;
    }
    public String asClassPath() {
        if(name.equals(IG_DEFAULT_PACKAGE_NAME)){
            return "classpath:org"+File.separator+"hl7"+File.separator+"fhir"+File.separator+ SupportedFhirVersion.toReleaseName(fhirVersion, true)+"packages"+File.separator + "hl7.fhir."+fhirVersion.name().toLowerCase()+ ".core-" +version + ARCHIVE_EXTENSION;
        }
        return "classpath:"+PACKAGE_ROOT_DIRECTORY + File.separator + String.join(File.separator, name.split("\\.")) + File.separator + name +"-" + version + ARCHIVE_EXTENSION;
    }
    public String asId(){
        if(name.equals(IG_DEFAULT_PACKAGE_NAME)){
            // hl7.fhir.r4.core-4.0.1
            return "hl7.fhir."+SupportedFhirVersion.toReleaseName(fhirVersion, true)+ ".core_" + version;
        }
        return name + "_"+ SupportedFhirVersion.toReleaseName(fhirVersion, true) +"_" + version;
    }

    public JsonObject asJsonObject(){
        return new JsonObject()
                .put("name", name)
                .put("version", version)
                .put("fhirVersion", fhirVersion.name());
    }
    public static IGPackageIdentity fromJson(JsonObject jsonObject){
        return new IGPackageIdentity(jsonObject.getString("name"), jsonObject.getString("version"), SupportedFhirVersion.valueOf(jsonObject.getString("fhirVersion").toUpperCase()));
    }
    public static IGPackageIdentity fromJson(String jsonString){
        JsonObject jsonObject = new JsonObject(jsonString);
        return fromJson(jsonObject);
    }
    public static IGPackageIdentity createIGPackageIdentityForCorePackage(FhirContext fhirContext){
        FhirCoreIgPackageType fhirCoreIgPackageType = FhirCoreIgPackageType.fromFhirContext(fhirContext);
        return new IGPackageIdentity(IGPackageIdentity.IG_DEFAULT_PACKAGE_NAME, fhirCoreIgPackageType.getVersion(), SupportedFhirVersion.fromVersionNumber(fhirContext.getVersion().getVersion().getFhirVersionString()));

    }
    public String getName() {
        return name;
    }
    public String getVersion() {
        return version;
    }
    public SupportedFhirVersion getFhirVersion() {
        return fhirVersion;
    }

    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((fhirVersion == null) ? 0 : fhirVersion.name().hashCode());
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((version == null) ? 0 : version.hashCode());
        return result;
    }
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        IGPackageIdentity that = (IGPackageIdentity) obj;
        return name.equals(that.name) &&
                version.equals(that.version) &&
                fhirVersion == that.fhirVersion;
    }


    @Override
    public String toString() {
        return "IGPackageIdentity{name='" + name + "', version='" + version + "', fhirVersion=" + fhirVersion + "}";
    }
}
