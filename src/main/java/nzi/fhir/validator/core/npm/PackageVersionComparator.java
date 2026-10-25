package nzi.fhir.validator.core.npm;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders FHIR package version labels by SemVer 2.0 precedence: numeric core first, a pre-release ranks below the
 * corresponding release, build metadata is ignored. Labels that are not SemVer-like ({@code current}, {@code dev},
 * {@code cibuild}, ...) sort below every SemVer label. Never throws.
 */
public final class PackageVersionComparator implements Comparator<String> {

    public static final PackageVersionComparator INSTANCE = new PackageVersionComparator();

    private static final Pattern SEMVER = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$");

    private PackageVersionComparator() {
    }

    /**
     * The "latest" version: the highest release if any release exists, otherwise the highest label overall
     * (pre-releases and non-SemVer labels are chosen only when no release is available).
     */
    public static Optional<String> max(Collection<String> versions) {
        Optional<String> release = versions.stream().filter(PackageVersionComparator::isRelease).max(INSTANCE);
        return release.isPresent() ? release : versions.stream().max(INSTANCE);
    }

    public static boolean isRelease(String version) {
        Parsed p = parse(version);
        return p != null && p.pre() == null;
    }

    private record Parsed(BigInteger[] core, String[] pre) {
    }

    private static Parsed parse(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = SEMVER.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        BigInteger[] core = new BigInteger[3];
        for (int i = 0; i < 3; i++) {
            String g = m.group(i + 1);
            core[i] = g == null ? BigInteger.ZERO : new BigInteger(g);
        }
        String pre = m.group(4);
        if (pre != null && (pre.isEmpty() || pre.startsWith(".") || pre.endsWith(".") || pre.contains(".."))) {
            return null;
        }
        return new Parsed(core, pre == null ? null : pre.split("\\."));
    }

    @Override
    public int compare(String a, String b) {
        Parsed pa = parse(a);
        Parsed pb = parse(b);
        if (pa == null || pb == null) {
            if (pa != null) {
                return 1;
            }
            if (pb != null) {
                return -1;
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
        for (int i = 0; i < 3; i++) {
            int c = pa.core()[i].compareTo(pb.core()[i]);
            if (c != 0) {
                return c;
            }
        }
        if (pa.pre() == null || pb.pre() == null) {
            return pa.pre() == null ? (pb.pre() == null ? 0 : 1) : -1;
        }
        int n = Math.min(pa.pre().length, pb.pre().length);
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(pa.pre()[i], pb.pre()[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.pre().length, pb.pre().length);
    }

    private static int compareIdentifier(String x, String y) {
        boolean xn = x.chars().allMatch(Character::isDigit);
        boolean yn = y.chars().allMatch(Character::isDigit);
        if (xn && yn) {
            return new BigInteger(x).compareTo(new BigInteger(y));
        }
        if (xn != yn) {
            return xn ? -1 : 1;
        }
        // Alphanumeric identifiers: compare a trailing number numerically so "ballot10" > "ballot2".
        Matcher mx = TRAILING_NUMBER.matcher(x);
        Matcher my = TRAILING_NUMBER.matcher(y);
        if (mx.matches() && my.matches() && mx.group(1).equals(my.group(1))) {
            return new BigInteger(mx.group(2)).compareTo(new BigInteger(my.group(2)));
        }
        return x.compareTo(y);
    }

    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?[^0-9])([0-9]+)$");
}
