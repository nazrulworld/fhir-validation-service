package nzi.fhir.validator.core.enums;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The FHIR versions one instance serves (spec 009, research R1): {@code FV_ENABLED_FHIR_VERSIONS}, or every version
 * this release supports ({@link SupportedFhirVersion#enabled()}) when it is unset. An operator setting narrows what a
 * deployment serves; it never adds a version (R6 stays unserved). Immutable.
 */
public final class ServedVersions {
    /** The name of the setting, for messages. */
    public static final String VARIABLE = "FV_ENABLED_FHIR_VERSIONS";

    /** Every version this release serves: the default. */
    public static final ServedVersions ALL = new ServedVersions(EnumSet.copyOf(SupportedFhirVersion.enabled()), false);

    private final EnumSet<SupportedFhirVersion> versions;
    private final List<SupportedFhirVersion> ordered;
    /** Whether the setting named at least one version (it may still name them all); for the startup log. */
    private final boolean configured;

    private ServedVersions(EnumSet<SupportedFhirVersion> versions, boolean configured) {
        this.versions = versions;
        this.ordered = List.copyOf(versions);
        this.configured = configured;
    }

    /** Exactly {@code versions}, which must all be versions this release serves. */
    public static ServedVersions of(SupportedFhirVersion... versions) {
        EnumSet<SupportedFhirVersion> set = EnumSet.noneOf(SupportedFhirVersion.class);
        for (SupportedFhirVersion v : versions) {
            if (!v.isEnabled()) {
                throw new IllegalArgumentException(v + " is not served by this release");
            }
            set.add(v);
        }
        return set.isEmpty() ? ALL : new ServedVersions(set, true);
    }

    /**
     * Parses the setting: names separated by commas, trimmed, in any case, duplicates ignored. Nothing left means
     * {@link #ALL} (FR-002). An unknown name, or a known one this release doesn't serve, is a configuration error
     * naming it (FR-003).
     *
     * @throws IllegalStateException for a name that is not a served version
     */
    public static ServedVersions parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        EnumSet<SupportedFhirVersion> set = EnumSet.noneOf(SupportedFhirVersion.class);
        for (String item : raw.split(",")) {
            String name = item.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            SupportedFhirVersion v;
            try {
                v = SupportedFhirVersion.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(VARIABLE + ": '" + item.trim() + "' is not a FHIR version this service serves "
                        + "(accepted: " + ALL.label() + ")");
            }
            if (!v.isEnabled()) {
                throw new IllegalStateException(VARIABLE + ": '" + name + "' is known but not served by this release "
                        + "(accepted: " + ALL.label() + ")");
            }
            set.add(v);
        }
        return set.isEmpty() ? ALL : new ServedVersions(set, true);
    }

    /** Whether {@code FV_ENABLED_FHIR_VERSIONS} named any version (otherwise every version is served by default). */
    public boolean isConfigured() {
        return configured;
    }

    public boolean contains(SupportedFhirVersion version) {
        return version != null && versions.contains(version);
    }

    /** The served version named by {@code param} (trimmed, any case), or empty. */
    public Optional<SupportedFhirVersion> find(String param) {
        return SupportedFhirVersion.parseEnabled(param).filter(versions::contains);
    }

    /** In declaration order (STU3, R4, R4B, R5). */
    public List<SupportedFhirVersion> asList() {
        return ordered;
    }

    /** Every version this release serves (the setting is unset or lists them all). */
    public boolean isAll() {
        return versions.equals(ALL.versions);
    }

    /** For messages and logs: {@code "R4, R5"}. */
    public String label() {
        return ordered.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ServedVersions other && other.versions.equals(versions);
    }

    @Override
    public int hashCode() {
        return versions.hashCode();
    }

    @Override
    public String toString() {
        return label();
    }
}
