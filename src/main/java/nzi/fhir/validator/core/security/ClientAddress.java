package nzi.fhir.validator.core.security;

import io.vertx.core.Handler;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The client address of a request (spec 008, research R3), resolved once per request and used by the admin network
 * allowlist, the last-used audit and usage accounting, so they never disagree (FR-004).
 * <p>
 * Forwarded headers are believed only when the immediate peer is a trusted proxy ({@code FV_AUTH_TRUSTED_PROXIES}),
 * and only the one header the proxies write is read ({@code FV_AUTH_FORWARDED_HEADER}: {@code X-Forwarded-For} by
 * default, or RFC 7239 {@code Forwarded}). The other header is never read: a proxy that appends to one passes a
 * client-supplied copy of the other through unchanged, so reading it would let any client choose its address. Walking
 * the chain from the right, trusted proxies are skipped and the first other address is the client. An entry that can't
 * be parsed ({@code unknown}, an obfuscated identifier) stops the walk, and the last trusted hop is used, so a forged
 * entry is never chosen. When every hop is trusted, the left-most one is the client.
 * <p>
 * A peer whose address can't be parsed has no client address ({@code null}): the admin allowlist then refuses it, and
 * the audit and usage record no address. It is never taken for loopback.
 */
public final class ClientAddress {
    /** Routing-context key of the resolved {@link InetAddress}. */
    public static final String KEY = "clientAddress";

    /** Which forwarding header the trusted proxies write ({@code FV_AUTH_FORWARDED_HEADER}). */
    public enum Header {
        X_FORWARDED_FOR("X-Forwarded-For"), FORWARDED("Forwarded");

        private final String name;

        Header(String name) {
            this.name = name;
        }

        public String headerName() {
            return name;
        }

        /** {@code x-forwarded-for} or {@code forwarded}, ignoring case; {@code null}/blank = the default. */
        public static Header parse(String value) {
            if (value == null || value.isBlank()) {
                return X_FORWARDED_FOR;
            }
            for (Header h : values()) {
                if (h.name.equalsIgnoreCase(value.strip())) {
                    return h;
                }
            }
            throw new IllegalArgumentException("'" + value + "' (allowed: x-forwarded-for, forwarded)");
        }
    }

    private ClientAddress() {
    }

    /** A root handler that stores the resolved address under {@link #KEY} (absent when it can't be determined). */
    public static Handler<RoutingContext> handler(CidrList trusted, Header header) {
        return ctx -> {
            SocketAddress remote = ctx.request().remoteAddress();
            InetAddress a = resolve(remote == null ? null : remote.hostAddress(),
                    ctx.request().headers().getAll(header.headerName()), header, trusted);
            if (a != null) {
                ctx.put(KEY, a);
            }
            ctx.put(RESOLVED, true);
            ctx.next();
        };
    }

    private static final String RESOLVED = "clientAddressResolved";

    /**
     * The address stored by {@link #handler}; the immediate peer when no handler ran; {@code null} when it can't be
     * determined.
     */
    public static InetAddress of(RoutingContext ctx) {
        InetAddress a = ctx.get(KEY);
        if (a != null || Boolean.TRUE.equals(ctx.get(RESOLVED))) {
            return a;
        }
        SocketAddress remote = ctx.request().remoteAddress();
        return remote == null ? null : Cidr.parseAddress(stripPortAndBrackets(remote.hostAddress()));
    }

    /**
     * Pure resolution (unit-tested).
     *
     * @param peer   the immediate peer's address literal
     * @param values the configured header's lines, in order
     * @return the client, or {@code null} when the peer's address can't be parsed
     */
    public static InetAddress resolve(String peer, List<String> values, Header header, CidrList trusted) {
        InetAddress peerAddress = peer == null ? null : Cidr.parseAddress(stripPortAndBrackets(peer));
        if (peerAddress == null) {
            return null;
        }
        if (trusted.isEmpty() || !trusted.contains(peerAddress)) {
            return peerAddress;
        }
        List<String> chain = header == Header.FORWARDED ? forwardedFor(values) : split(values);
        InetAddress lastTrusted = peerAddress;
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress hop = Cidr.parseAddress(stripPortAndBrackets(chain.get(i)));
            if (hop == null) {
                return lastTrusted;
            }
            if (!trusted.contains(hop)) {
                return hop;
            }
            lastTrusted = hop;
        }
        return lastTrusted;
    }

    private static List<String> split(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                for (String part : line.split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.strip());
                    }
                }
            }
        }
        return out;
    }

    /** The {@code for=} values of RFC 7239 elements, in order (an element without one is skipped). */
    private static List<String> forwardedFor(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String element : split(lines)) {
            for (String pair : element.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).strip().toLowerCase(Locale.ROOT).equals("for")) {
                    String v = pair.substring(eq + 1).strip();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * {@code [2001:db8::1]:4711} → {@code 2001:db8::1}; {@code 203.0.113.7:5555} → {@code 203.0.113.7};
     * {@code fe80::1%eth0} → {@code fe80::1} (the zone only names the local interface).
     */
    static String stripPortAndBrackets(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            s = end < 0 ? s : s.substring(1, end);
        }
        int zone = s.indexOf('%');
        if (zone > 0 && s.indexOf(':') >= 0) {
            return s.substring(0, zone);
        }
        if (s.indexOf(':') >= 0 && s.indexOf(':') != s.lastIndexOf(':')) {
            return s; // IPv6 without brackets
        }
        int colon = s.indexOf(':');
        if (colon > 0 && s.indexOf(':', colon + 1) < 0) {
            return s.substring(0, colon); // IPv4 with a port
        }
        return s;
    }
}
