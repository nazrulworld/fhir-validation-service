package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.List;
import java.util.Objects;

/**
 * Identity of one validator context (spec 004, data-model §2.1): a FHIR version plus the IG packages it validates
 * against, or {@code null} for the core-only base context. Since spec 012 a context holds one IG ({@code name#version})
 * or two ({@code A#a|B#b}, in include order; data-model §1). The key is one string so everything keyed by it today
 * (configuration row, snapshots table, notifications, caches) works unchanged (research R1).
 *
 * @param igPackage a concrete {@code name#version}, a two-IG key {@code A#a|B#b}, or {@code null} for the base context
 */
public record SnapshotKey(SupportedFhirVersion fhirVersion, String igPackage) {
    /** Separates the two IGs of a two-IG key. */
    public static final char MEMBER_SEPARATOR = '|';
    /** Longest key: the validate {@code ig} parameter's limit, so every key can be named (research R2). */
    public static final int MAX_KEY_LENGTH = 256;

    public SnapshotKey {
        Objects.requireNonNull(fhirVersion, "fhirVersion");
        if (igPackage != null) {
            validate(igPackage);
        }
    }

    private static void validate(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("A context key is at most " + MAX_KEY_LENGTH + " characters");
        }
        int sep = key.indexOf(MEMBER_SEPARATOR);
        if (sep < 0) {
            requireMember(key);
            return;
        }
        String first = key.substring(0, sep);
        String second = key.substring(sep + 1);
        if (second.indexOf(MEMBER_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("A context holds at most two IG packages");
        }
        requireMember(first);
        requireMember(second);
        if (first.substring(0, first.indexOf('#')).equals(second.substring(0, second.indexOf('#')))) {
            throw new IllegalArgumentException("The two IG packages of a context must be different packages");
        }
    }

    private static void requireMember(String member) {
        int hash = member.indexOf('#');
        if (hash <= 0 || hash == member.length() - 1) {
            throw new IllegalArgumentException("igPackage must be a concrete name#version");
        }
    }

    public static SnapshotKey base(SupportedFhirVersion fhirVersion) {
        return new SnapshotKey(fhirVersion, null);
    }

    public static SnapshotKey of(SupportedFhirVersion fhirVersion, String igPackage) {
        return new SnapshotKey(fhirVersion, igPackage);
    }

    /** The two-IG context of {@code first} and {@code second}, in that order (spec 012 FR-007). */
    public static SnapshotKey combined(SupportedFhirVersion fhirVersion, String first, String second) {
        return new SnapshotKey(fhirVersion, first + MEMBER_SEPARATOR + second);
    }

    /** The IG packages of a key string, in key order: empty for {@code null}, one or two {@code name#version}. */
    public static List<String> membersOf(String igPackage) {
        if (igPackage == null) {
            return List.of();
        }
        int sep = igPackage.indexOf(MEMBER_SEPARATOR);
        return sep < 0 ? List.of(igPackage) : List.of(igPackage.substring(0, sep), igPackage.substring(sep + 1));
    }

    /** Whether a key string names a two-IG context. */
    public static boolean isCombined(String igPackage) {
        return igPackage != null && igPackage.indexOf(MEMBER_SEPARATOR) >= 0;
    }

    public boolean isBase() {
        return igPackage == null;
    }

    /** The context's IG packages in key order (spec 012 data-model §1). */
    public List<String> members() {
        return membersOf(igPackage);
    }

    /** Whether this context holds two IG packages. */
    public boolean isCombined() {
        return isCombined(igPackage);
    }

    /** {@code name#version}, {@code A#a|B#b} or {@code base}: the value of the {@code X-Validation-Context} header. */
    public String contextLabel() {
        return igPackage == null ? "base" : igPackage;
    }

    @Override
    public String toString() {
        return fhirVersion.name() + ":" + contextLabel();
    }
}
