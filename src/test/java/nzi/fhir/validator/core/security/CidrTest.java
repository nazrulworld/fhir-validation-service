package nzi.fhir.validator.core.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 research R4: IPv4/IPv6 CIDR parsing and matching, without DNS. */
class CidrTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void ipv4Networks() throws Exception {
        Cidr ten = Cidr.parse("10.0.0.0/8");
        assertTrue(ten.contains(ip("10.255.1.2")));
        assertFalse(ten.contains(ip("11.0.0.1")));
        assertTrue(Cidr.parse("10.1.2.3/32").contains(ip("10.1.2.3")));
        assertFalse(Cidr.parse("10.1.2.3/32").contains(ip("10.1.2.4")));
        assertTrue(Cidr.parse("0.0.0.0/0").contains(ip("203.0.113.7")));
        assertTrue(Cidr.parse("192.168.1.77/24").contains(ip("192.168.1.1")), "host bits are masked on parse");
        assertTrue(Cidr.parse("172.16.0.0/12").contains(ip("172.31.255.255")));
        assertFalse(Cidr.parse("172.16.0.0/12").contains(ip("172.32.0.0")));
    }

    @Test
    void ipv6Networks() throws Exception {
        assertTrue(Cidr.parse("2001:db8::/32").contains(ip("2001:db8:1::5")));
        assertFalse(Cidr.parse("2001:db8::/32").contains(ip("2001:db9::1")));
        assertTrue(Cidr.parse("::/0").contains(ip("2001:db8::1")));
        assertTrue(Cidr.parse("2001:db8::1/128").contains(ip("2001:db8::1")));
        assertFalse(Cidr.parse("2001:db8::1/128").contains(ip("2001:db8::2")));
        assertFalse(Cidr.parse("::/0").contains(ip("10.0.0.1")), "an IPv6 network never matches IPv4");
    }

    @Test
    void ipv4MappedAddressesAreTreatedAsIpv4() throws Exception {
        assertTrue(Cidr.parse("10.0.0.0/8").contains(InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 10, 1, 2, 3})));
        assertTrue(Cidr.parse("::ffff:10.1.2.3").contains(ip("10.1.2.3")));
    }

    @Test
    void bareAddressIsAHostNetwork() throws Exception {
        assertEquals(32, Cidr.parse("127.0.0.1").prefix());
        assertEquals(128, Cidr.parse("::1").prefix());
        assertTrue(Cidr.parse("127.0.0.1").contains(ip("127.0.0.1")));
    }

    @Test
    void invalidEntriesAreRefusedNamingTheEntry() {
        for (String bad : new String[]{"10.0.0.0/33", "2001:db8::/129", "example.com", "10.0.0", "", "1.2.3.4/x",
                "256.0.0.1", "10.0.0.0/-1", "localhost"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), bad);
            assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
        }
    }

    @Test
    void namesAreNeverResolved() {
        // A name would need DNS; it is refused before any lookup (only [0-9A-Fa-f:.] literals reach InetAddress).
        assertNull(Cidr.parseAddress("localhost"));
        assertNull(Cidr.parseAddress("dead.beef"));
        assertNull(Cidr.parseAddress("cafe"));
        // Code review regression: these pass the character check but would reach the system resolver.
        assertNull(Cidr.parseAddress(".:"));
        assertNull(Cidr.parseAddress("..::1"));
        assertNull(Cidr.parseAddress(".1.2.3:"));
        assertNull(Cidr.parseAddress("::1..2"));
        assertNotNull(Cidr.parseAddress("::1"));
        assertNotNull(Cidr.parseAddress("::ffff:10.1.2.3"));
        assertNotNull(Cidr.parseAddress("fe80::1"));
    }

    @Test
    void lists() throws Exception {
        CidrList list = CidrList.parse(" 10.0.0.0/8 , 192.168.0.0/16,,");
        assertEquals(2, list.networks().size());
        assertTrue(list.contains(ip("192.168.4.4")));
        assertFalse(list.contains(ip("8.8.8.8")));
        assertTrue(CidrList.parse(null).isEmpty());
        assertTrue(CidrList.parse("  ").isEmpty());
        assertFalse(CidrList.EMPTY.contains(ip("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> CidrList.parse("10.0.0.0/8, nope"));
    }
}
