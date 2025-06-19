package nzi.fhir.validator.core.model;

import org.apache.commons.lang3.Validate;

public class IgPackageName {

    private final String name;
    private final String version;

    public IgPackageName(String name, String version) {
        Validate.notNull(name, "name cannot be null");
        Validate.notNull(version, "version cannot be null");
        this.name = name;
        this.version = version;
    }
    public static IgPackageName fromIdAndVersion(String idAndVersion){
        String name = idAndVersion.contains("#") ? idAndVersion.substring(0, idAndVersion.indexOf("#")) : idAndVersion;
        String version = idAndVersion.contains("#") ? idAndVersion.substring(idAndVersion.indexOf("#") + 1) : null;
        Validate.notNull(version, "name cannot be null");
        return new IgPackageName(name, version);
    }

    public String getName() {
        return name;
    }
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "IgPackageName{" + "name='" + name + '\'' + ", version='" + version + '\'' + '}';
    }
}
