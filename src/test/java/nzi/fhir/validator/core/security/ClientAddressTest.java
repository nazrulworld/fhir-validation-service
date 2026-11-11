package nzi.fhir.validator.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 008 research R3: the trusted-proxy rule. */
class ClientAddressTest {
    private static final CidrList LOCAL = CidrList.parse("127.0.0.1/32");
    private static final CidrList LOCAL_AND_TEN = CidrList.parse("127.0.0.1/32,10.0.0.0/8");

    private static String resolve(String peer, List<String> forwarded, List<String> xff, CidrList trusted) {
        return ClientAddress.resolve(peer, forwarded, xff, trusted).getHostAddress();
    }

    @Test
    void withoutTrustedProxiesHeadersAreIgnored() {
        assertEquals("127.0.0.1", resolve("127.0.0.1", List.of(), List.of("203.0.113.7"), CidrList.EMPTY));
    }

    @Test
    void anUntrustedPeerCannotSpoofItsAddress() {
        assertEquals("198.51.100.9", resolve("198.51.100.9", List.of("for=10.0.0.1"), List.of("10.0.0.1"), LOCAL));
    }

    @Test
    void aTrustedProxyForwardsTheClient() {
        assertEquals("203.0.113.7", resolve("127.0.0.1", List.of(), List.of("203.0.113.7"), LOCAL));
    }

    @Test
    void theRightMostUntrustedHopWins() {
        assertEquals("203.0.113.7", resolve("127.0.0.1", List.of(), List.of("10.1.1.1, 203.0.113.7"), LOCAL));
        assertEquals("203.0.113.7", resolve("127.0.0.1", List.of(), List.of("203.0.113.7, 10.0.0.5"), LOCAL_AND_TEN));
    }

    @Test
    void whenEveryHopIsTrustedTheLeftMostIsUsed() {
        assertEquals("10.0.0.9", resolve("127.0.0.1", List.of(), List.of("10.0.0.9, 10.0.0.5"), LOCAL_AND_TEN));
    }

    @Test
    void forwardedTakesPrecedenceOverXForwardedFor() {
        assertEquals("2001:db8:0:0:0:0:0:1", resolve("127.0.0.1",
                List.of("for=198.51.100.1;proto=https, for=\"[2001:db8::1]:4711\""), List.of("203.0.113.7"), LOCAL));
    }

    @Test
    void anUnparseableEntryStopsTheWalk() {
        assertEquals("10.0.0.5", resolve("127.0.0.1", List.of(), List.of("203.0.113.7, unknown, 10.0.0.5"), LOCAL_AND_TEN));
        assertEquals("127.0.0.1", resolve("127.0.0.1", List.of("for=unknown"), List.of(), LOCAL));
        assertEquals("127.0.0.1", resolve("127.0.0.1", List.of("for=_hidden"), List.of(), LOCAL));
    }

    @Test
    void portsAreStripped() {
        assertEquals("203.0.113.7", resolve("127.0.0.1", List.of(), List.of("203.0.113.7:5555"), LOCAL));
    }

    @Test
    void severalHeaderLinesAreJoinedInOrder() {
        assertEquals("198.51.100.2", resolve("127.0.0.1", List.of(), List.of("203.0.113.7", "198.51.100.2"), LOCAL));
    }

    @Test
    void noPeerMeansLoopback() {
        assertEquals("127.0.0.1", resolve(null, List.of(), List.of(), CidrList.EMPTY));
    }
}
