package nzi.fhir.validator.core.terminology;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-011: only public addresses are ever contacted for terminology. */
class NetworkGuardTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void refusesEveryNonPublicAddress() throws UnknownHostException {
        NetworkGuard guard = NetworkGuard.strict();
        for (String a : new String[]{"127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0",
                "100.64.0.1", "::1", "fc00::1", "fe80::1"}) {
            assertFalse(guard.permitted(ip(a)), a);
        }
        assertTrue(guard.permitted(ip("8.8.8.8")));
        assertTrue(guard.permitted(ip("2001:4860:4860::8888")));
    }

    @Test
    void resolvingAPrivateHostFails() {
        assertThrows(UnknownHostException.class, () -> NetworkGuard.strict().resolve("localhost"));
        assertThrows(UnknownHostException.class, () -> NetworkGuard.strict().resolve("127.0.0.1"));
        assertThrows(UnknownHostException.class, () -> NetworkGuard.strict().resolve("10.1.2.3"));
    }

    @Test
    void onlyTheTestGuardAllowsLoopback() throws UnknownHostException {
        assertFalse(NetworkGuard.strict().allowsLoopback(), "no exemption in production");
        NetworkGuard test = NetworkGuard.forTests();
        assertTrue(test.permitted(ip("127.0.0.1")));
        assertEquals(1, test.resolve("127.0.0.1").length);
        assertFalse(test.permitted(ip("10.0.0.5")), "loopback only, never private networks");
    }
}
