package nzi.fhir.validator.core.security;

import nzi.fhir.validator.core.security.ClientAddress.Header;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 research R3: the trusted-proxy rule. */
class ClientAddressTest {
    private static final CidrList LOCAL = CidrList.parse("127.0.0.1/32");
    private static final CidrList LOCAL_AND_TEN = CidrList.parse("127.0.0.1/32,10.0.0.0/8");

    private static String xff(String peer, List<String> xff, CidrList trusted) {
        return ClientAddress.resolve(peer, xff, Header.X_FORWARDED_FOR, trusted).getHostAddress();
    }

    @Test
    void withoutTrustedProxiesHeadersAreIgnored() {
        assertEquals("127.0.0.1", xff("127.0.0.1", List.of("203.0.113.7"), CidrList.EMPTY));
    }

    @Test
    void anUntrustedPeerCannotSpoofItsAddress() {
        assertEquals("198.51.100.9", xff("198.51.100.9", List.of("10.0.0.1"), LOCAL));
    }

    @Test
    void aTrustedProxyForwardsTheClient() {
        assertEquals("203.0.113.7", xff("127.0.0.1", List.of("203.0.113.7"), LOCAL));
    }

    @Test
    void theRightMostUntrustedHopWins() {
        assertEquals("203.0.113.7", xff("127.0.0.1", List.of("10.1.1.1, 203.0.113.7"), LOCAL));
        assertEquals("203.0.113.7", xff("127.0.0.1", List.of("203.0.113.7, 10.0.0.5"), LOCAL_AND_TEN));
    }

    @Test
    void whenEveryHopIsTrustedTheLeftMostIsUsed() {
        assertEquals("10.0.0.9", xff("127.0.0.1", List.of("10.0.0.9, 10.0.0.5"), LOCAL_AND_TEN));
    }

    @Test
    void theForwardedHeaderIsReadWhenConfigured() {
        assertEquals("2001:db8:0:0:0:0:0:1", ClientAddress.resolve("127.0.0.1",
                List.of("for=198.51.100.1;proto=https, for=\"[2001:db8::1]:4711\""), Header.FORWARDED, LOCAL).getHostAddress());
    }

    /**
     * Review regression: behind a proxy that appends only X-Forwarded-For, a client-supplied {@code Forwarded} header
     * passes through unchanged. It must never be read, or any client could pick an allowlisted address.
     */
    @Test
    void onlyTheConfiguredHeaderIsRead() {
        // The handler passes only the configured header's lines; resolve() never sees the other one.
        assertEquals("198.51.100.66", xff("127.0.0.1", List.of("198.51.100.66"), LOCAL));
        assertEquals(Header.X_FORWARDED_FOR, Header.parse(null), "X-Forwarded-For is the default");
        assertEquals(Header.FORWARDED, Header.parse(" Forwarded "));
        assertThrows(IllegalArgumentException.class, () -> Header.parse("both"));
    }

    @Test
    void anUnparseableEntryStopsTheWalk() {
        assertEquals("10.0.0.5", xff("127.0.0.1", List.of("203.0.113.7, unknown, 10.0.0.5"), LOCAL_AND_TEN));
        assertEquals("127.0.0.1", ClientAddress.resolve("127.0.0.1", List.of("for=unknown"), Header.FORWARDED, LOCAL).getHostAddress());
        assertEquals("127.0.0.1", ClientAddress.resolve("127.0.0.1", List.of("for=_hidden"), Header.FORWARDED, LOCAL).getHostAddress());
    }

    @Test
    void portsAreStripped() {
        assertEquals("203.0.113.7", xff("127.0.0.1", List.of("203.0.113.7:5555"), LOCAL));
    }

    @Test
    void severalHeaderLinesAreJoinedInOrder() {
        assertEquals("198.51.100.2", xff("127.0.0.1", List.of("203.0.113.7", "198.51.100.2"), LOCAL));
    }

    /** Review regression: an unparseable peer is unknown, never loopback (which a localhost allowlist would admit). */
    @Test
    void anUnparseablePeerHasNoAddress() {
        assertNull(ClientAddress.resolve(null, List.of(), Header.X_FORWARDED_FOR, CidrList.EMPTY));
        assertNull(ClientAddress.resolve("not-an-address", List.of(), Header.X_FORWARDED_FOR, CidrList.EMPTY));
        assertFalse(CidrList.parse("127.0.0.1/32,::1/128").contains(null), "an unknown address is never allowlisted");
    }

    /** Review regression: a link-local peer with a zone ({@code fe80::1%eth0}) is that peer, not loopback. */
    @Test
    void ipv6ZonesAreStripped() throws Exception {
        InetAddress a = ClientAddress.resolve("fe80::1%eth0", List.of(), Header.X_FORWARDED_FOR, CidrList.EMPTY);
        assertNotNull(a);
        assertEquals(InetAddress.getByName("fe80::1"), a);
        assertFalse(CidrList.parse("127.0.0.1/32,::1/128").contains(a));
        assertEquals("fe80::1", ClientAddress.stripPortAndBrackets("[fe80::1%25]:80".replace("%25", "%en0")));
    }
}
