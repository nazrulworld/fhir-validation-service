package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.security.Access;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Access of the included (pinned) validator contexts of one FHIR version (spec 005, data-model §1.4), keyed by
 * {@code name#version}. Immutable: {@code ConfigSync} swaps the whole object when the configuration changes.
 */
public final class PinnedAccess {
    public static final PinnedAccess EMPTY = new PinnedAccess(Map.of());

    private final Map<String, Access> access;

    private PinnedAccess(Map<String, Access> access) {
        this.access = Map.copyOf(access);
    }

    /**
     * @param included every included {@code name#version} of the version
     * @param rows     the {@code fhir_validator_snapshots} rows; an included entry without a row (written by a 3.0
     *                 instance during a rolling upgrade) is owned by the system organisation and shareable, the
     *                 migration default
     */
    public static PinnedAccess of(Collection<String> included, Map<String, Access> rows, String systemShortName) {
        Map<String, Access> access = new HashMap<>();
        for (String key : included) {
            Access row = rows.get(key);
            access.put(key, row != null ? row : Access.systemShared(systemShortName));
        }
        return new PinnedAccess(access);
    }

    /** The access of an included context, or {@code null} when {@code igKey} isn't included (on-demand). */
    public Access get(String igKey) {
        return access.get(igKey);
    }

    public boolean isPinned(String igKey) {
        return access.containsKey(igKey);
    }

    public Map<String, Access> asMap() {
        return access;
    }

    /** A copy with the access of {@code igKey} replaced (tests). */
    public PinnedAccess with(String igKey, Access a) {
        Map<String, Access> copy = new HashMap<>(access);
        copy.put(igKey, a);
        return new PinnedAccess(copy);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PinnedAccess p && p.access.equals(access);
    }

    @Override
    public int hashCode() {
        return access.hashCode();
    }

    @Override
    public String toString() {
        return "PinnedAccess" + access;
    }
}
