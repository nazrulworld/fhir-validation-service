package nzi.fhir.validator.core.terminology;

import java.util.Map;

/**
 * The configured terminology setting of each included context of one FHIR version (spec 010 FR-002, data-model §2):
 * the default a validation request uses unless it names {@code tx}/{@code txMode}. Immutable; {@code ConfigSync}
 * swaps the whole object on every sync, like {@code PinnedAccess}. Not part of what a context is built from.
 */
public final class TerminologyDefaults {
    public static final TerminologyDefaults EMPTY = new TerminologyDefaults(Map.of());

    private final Map<String, TerminologySettings> settings;

    public TerminologyDefaults(Map<String, TerminologySettings> settings) {
        this.settings = Map.copyOf(settings);
    }

    /**
     * The default of {@code igKey}; {@link TerminologySettings#NONE} for a context with no setting and for the base
     * context ({@code null}).
     */
    public TerminologySettings get(String igKey) {
        if (igKey == null) {
            return TerminologySettings.NONE;
        }
        return settings.getOrDefault(igKey, TerminologySettings.NONE);
    }

    public Map<String, TerminologySettings> asMap() {
        return settings;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TerminologyDefaults other && settings.equals(other.settings);
    }

    @Override
    public int hashCode() {
        return settings.hashCode();
    }
}
