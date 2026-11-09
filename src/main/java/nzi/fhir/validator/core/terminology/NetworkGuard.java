package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.core.npm.SafePackageFetcher;
import org.apache.http.conn.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The address check of every terminology call (spec 007 FR-011, research R11): a DNS resolver that refuses a host
 * name when <em>any</em> of its addresses is not public. The HTTP client connects only to addresses this resolver
 * returned, so the check also covers DNS rebinding. Registry servers are public; there is no exemption in production.
 */
public final class NetworkGuard implements DnsResolver {
    private static final NetworkGuard STRICT = new NetworkGuard(false);

    private final boolean allowLoopback;

    private NetworkGuard(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** The production guard: public addresses only. */
    public static NetworkGuard strict() {
        return STRICT;
    }

    /** Tests only: also allows loopback addresses, so local stub servers can be reached (research R14). */
    static NetworkGuard forTests() {
        return new NetworkGuard(true);
    }

    public boolean allowsLoopback() {
        return allowLoopback;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress a : addresses) {
            if (!permitted(a)) {
                throw new UnknownHostException("non-public address refused for terminology: " + host);
            }
        }
        return addresses;
    }

    /** Whether one address may be contacted. */
    public boolean permitted(InetAddress a) {
        if (allowLoopback && a.isLoopbackAddress()) {
            return true;
        }
        return !SafePackageFetcher.isNonPublicAddress(a);
    }
}
