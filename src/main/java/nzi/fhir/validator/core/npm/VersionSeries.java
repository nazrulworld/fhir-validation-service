package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.web.ProblemException;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A requested version series such as {@code 1.2.x}, {@code 1.x.x} or {@code 1.x} (spec 006 FR-001 to FR-005,
 * data-model §1): fixed leading numeric parts followed by trailing {@code x}/{@code X} wildcard parts, at most three
 * parts in total. A version matches when its release parts (as {@link PackageVersionComparator} parses them, missing
 * parts counting as {@code 0}) equal the fixed parts; the highest match follows {@link PackageVersionComparator#max}
 * (a release before a pre-release). A series is request input only and is never stored.
 */
public final class VersionSeries {
    static final String INVALID_MESSAGE = "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)";
    private static final Pattern GRAMMAR = Pattern.compile("^\\d+\\.[xX](\\.[xX])?$|^\\d+\\.\\d+\\.[xX]$");

    private final String raw;
    private final BigInteger[] fixed;

    private VersionSeries(String raw, BigInteger[] fixed) {
        this.raw = raw;
        this.fixed = fixed;
    }

    /**
     * Whether {@code version} is meant as a series: any dot-separated part is exactly {@code x} or {@code X}. Such a
     * value is either a valid series or rejected by {@link #parse}; it is never used as a literal version.
     */
    public static boolean isSeries(String version) {
        if (version == null) {
            return false;
        }
        for (String part : version.split("\\.", -1)) {
            if (part.equalsIgnoreCase("x")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @throws ProblemException 400 {@code invalid-request} unless {@code version} is {@code N.x}, {@code N.x.x} or
     *                          {@code N.N.x}
     */
    public static VersionSeries parse(String version) {
        if (version == null || !GRAMMAR.matcher(version).matches()) {
            throw new ProblemException(400, "invalid-request", INVALID_MESSAGE);
        }
        String[] parts = version.split("\\.");
        int numeric = 0;
        while (numeric < parts.length && !parts[numeric].equalsIgnoreCase("x")) {
            numeric++;
        }
        BigInteger[] fixed = new BigInteger[numeric];
        for (int i = 0; i < numeric; i++) {
            fixed[i] = new BigInteger(parts[i]);
        }
        return new VersionSeries(version, fixed);
    }

    /** The value as requested, for messages and {@code requestedVersion}. */
    public String raw() {
        return raw;
    }

    public boolean matches(String version) {
        Optional<BigInteger[]> parts = PackageVersionComparator.releaseParts(version);
        if (parts.isEmpty()) {
            return false;
        }
        for (int i = 0; i < fixed.length; i++) {
            if (!parts.get()[i].equals(fixed[i])) {
                return false;
            }
        }
        return true;
    }

    /** The highest matching candidate: a release if any matches, otherwise the highest pre-release. */
    public Optional<String> highest(Collection<String> candidates) {
        return PackageVersionComparator.max(candidates.stream().filter(this::matches).toList());
    }

    @Override
    public String toString() {
        return raw;
    }
}
