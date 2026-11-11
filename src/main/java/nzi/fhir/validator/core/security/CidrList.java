package nzi.fhir.validator.core.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable list of {@link Cidr} networks parsed from a comma-separated setting ({@code FV_AUTH_ADMIN_CIDRS},
 * {@code FV_AUTH_TRUSTED_PROXIES}; spec 008). An unset or blank setting is {@link #EMPTY}, which contains nothing.
 */
public final class CidrList {
    public static final CidrList EMPTY = new CidrList(List.of());

    private final List<Cidr> networks;

    private CidrList(List<Cidr> networks) {
        this.networks = List.copyOf(networks);
    }

    /**
     * @throws IllegalArgumentException naming the first invalid entry
     */
    public static CidrList parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return EMPTY;
        }
        List<Cidr> out = new ArrayList<>();
        for (String entry : commaSeparated.split(",")) {
            if (!entry.isBlank()) {
                out.add(Cidr.parse(entry.strip()));
            }
        }
        return out.isEmpty() ? EMPTY : new CidrList(out);
    }

    public boolean contains(InetAddress address) {
        for (Cidr c : networks) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return networks.isEmpty();
    }

    public List<Cidr> networks() {
        return networks;
    }

    @Override
    public String toString() {
        return networks.toString();
    }
}
