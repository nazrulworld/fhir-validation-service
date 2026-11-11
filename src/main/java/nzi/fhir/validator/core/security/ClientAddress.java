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
 * Forwarded headers are believed only when the immediate peer is a trusted proxy ({@code FV_AUTH_TRUSTED_PROXIES}).
 * The chain comes from {@code Forwarded} (RFC 7239, {@code for=}) or, only when that header is absent, from
 * {@code X-Forwarded-For}. Walking it from the right, trusted proxies are skipped and the first other address is the
 * client. An entry that can't be parsed ({@code unknown}, an obfuscated identifier) stops the walk, and the last trusted
 * hop is used, so a forged entry is never chosen. When every hop is trusted, the left-most one is the client.
 */
public final class ClientAddress {
    /** Routing-context key of the resolved {@link InetAddress}. */
    public static final String KEY = "clientAddress";

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private ClientAddress() {
    }

    /** A root handler that stores the resolved address under {@link #KEY}. */
    public static Handler<RoutingContext> handler(CidrList trusted) {
        return ctx -> {
            ctx.put(KEY, resolve(ctx, trusted));
            ctx.next();
        };
    }

    /** The address stored by {@link #handler}, or the immediate peer when no handler ran. */
    public static InetAddress of(RoutingContext ctx) {
        InetAddress a = ctx.get(KEY);
        return a != null ? a : peer(ctx);
    }

    static InetAddress resolve(RoutingContext ctx, CidrList trusted) {
        SocketAddress remote = ctx.request().remoteAddress();
        String peer = remote == null ? null : remote.hostAddress();
        return resolve(peer, ctx.request().headers().getAll("Forwarded"), ctx.request().headers().getAll("X-Forwarded-For"),
                trusted);
    }

    /**
     * Pure resolution (unit-tested).
     *
     * @param peer      the immediate peer's address literal ({@code null}: loopback)
     * @param forwarded the {@code Forwarded} header lines, in order
     * @param xff       the {@code X-Forwarded-For} header lines, in order
     */
    public static InetAddress resolve(String peer, List<String> forwarded, List<String> xff, CidrList trusted) {
        InetAddress peerAddress = peer == null ? null : Cidr.parseAddress(stripPortAndBrackets(peer));
        if (peerAddress == null) {
            peerAddress = LOOPBACK;
        }
        if (trusted.isEmpty() || !trusted.contains(peerAddress)) {
            return peerAddress;
        }
        List<String> chain = forwarded != null && !forwarded.isEmpty() ? forwardedFor(forwarded) : split(xff);
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

    private static InetAddress peer(RoutingContext ctx) {
        SocketAddress remote = ctx.request().remoteAddress();
        InetAddress a = remote == null ? null : Cidr.parseAddress(stripPortAndBrackets(remote.hostAddress()));
        return a != null ? a : LOOPBACK;
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

    /** {@code [2001:db8::1]:4711} → {@code 2001:db8::1}; {@code 203.0.113.7:5555} → {@code 203.0.113.7}. */
    static String stripPortAndBrackets(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end < 0 ? s : s.substring(1, end);
        }
        int colon = s.indexOf(':');
        if (colon > 0 && s.indexOf(':', colon + 1) < 0) {
            return s.substring(0, colon); // IPv4 with a port
        }
        return s;
    }
}
