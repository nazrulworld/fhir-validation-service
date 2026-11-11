package nzi.fhir.validator.core.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 network in CIDR notation (spec 008, research R4), for the admin network allowlist and the trusted
 * proxy list. Only address literals are accepted, never host names, so parsing never performs a DNS lookup. A bare
 * address is a single-host network. IPv4-mapped IPv6 addresses ({@code ::ffff:a.b.c.d}) are treated as IPv4.
 *
 * @param network the network address (host bits cleared), 4 or 16 bytes
 * @param prefix  the prefix length, 0 to 32 (IPv4) or 0 to 128 (IPv6)
 */
public record Cidr(byte[] network, int prefix) {
    private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    /**
     * Parses {@code 10.0.0.0/8}, {@code 2001:db8::/32}, or a bare address.
     *
     * @throws IllegalArgumentException naming the entry when it is not a valid network
     */
    public static Cidr parse(String text) {
        String s = text == null ? "" : text.strip();
        int slash = s.indexOf('/');
        String address = slash < 0 ? s : s.substring(0, slash);
        InetAddress parsed = literal(address, text);
        byte[] bytes = normalise(parsed);
        int max = bytes.length * 8;
        int prefix = max;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw invalid(text);
            }
            if (prefix < 0 || prefix > max) {
                throw invalid(text);
            }
        }
        return new Cidr(mask(bytes, prefix), prefix);
    }

    /** An address literal (no DNS); {@code null} when {@code text} is not one. */
    static InetAddress parseAddress(String text) {
        if (text == null || text.isEmpty() || !LITERAL.matcher(text).matches() || (text.indexOf(':') < 0 && !isDottedQuad(text))) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public boolean contains(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] candidate = normalise(address);
        if (candidate.length != network.length) {
            return false;
        }
        return Arrays.equals(mask(candidate, prefix), network);
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException e) {
            return Arrays.toString(network) + "/" + prefix;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr c && c.prefix == prefix && Arrays.equals(c.network, network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefix;
    }

    private static InetAddress literal(String address, String entry) {
        InetAddress a = parseAddress(address);
        if (a == null) {
            throw invalid(entry);
        }
        return a;
    }

    private static boolean isDottedQuad(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit) || Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** The address bytes, with IPv4-mapped IPv6 reduced to IPv4. */
    static byte[] normalise(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address && isMappedV4(b)) {
            return Arrays.copyOfRange(b, 12, 16);
        }
        return a instanceof Inet4Address || b.length == 4 ? b : b.clone();
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static byte[] mask(byte[] bytes, int prefix) {
        byte[] out = bytes.clone();
        for (int i = 0; i < out.length; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            out[i] = (byte) (out[i] & (bits == 0 ? 0 : (0xff << (8 - bits))));
        }
        return out;
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException("invalid network '" + entry + "'");
    }
}
