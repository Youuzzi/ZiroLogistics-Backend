package com.zirocraft.zirologistics.wms.security.throttling.application;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Resolves a source address without trusting forwarding headers from arbitrary peers.
 *
 * Configure only the CIDRs of reverse proxies that are directly controlled by the
 * deployment. Invalid or oversized forwarding metadata falls back to the socket peer.
 */
public final class WmsClientAddressResolver {

    private static final int MAX_FORWARDED_HEADER_LENGTH = 2_048;
    private static final int MAX_FORWARDED_HOPS = 16;

    private final List<IpNetwork> trustedProxies;

    public WmsClientAddressResolver(Collection<String> trustedProxyCidrs) {
        Objects.requireNonNull(trustedProxyCidrs, "trustedProxyCidrs must not be null");
        this.trustedProxies = trustedProxyCidrs.stream()
                .map(IpNetwork::parse)
                .toList();
    }

    /**
     * @param socketPeerAddress numeric address of the TCP peer, not a forwarded value
     * @param forwardedForHeader raw X-Forwarded-For header, if present
     * @return canonical numeric address to use as the source throttle identity
     */
    public String resolve(String socketPeerAddress, String forwardedForHeader) {
        IpAddress peer = IpAddress.parse(socketPeerAddress);
        if (!isTrusted(peer) || forwardedForHeader == null || forwardedForHeader.isBlank()
                || forwardedForHeader.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return peer.canonical();
        }

        List<IpAddress> forwarded = parseForwardedChain(forwardedForHeader);
        if (forwarded == null || forwarded.isEmpty() || forwarded.size() > MAX_FORWARDED_HOPS) {
            return peer.canonical();
        }

        IpAddress currentHop = peer;
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            if (!isTrusted(currentHop)) {
                return currentHop.canonical();
            }
            currentHop = forwarded.get(i);
        }
        return currentHop.canonical();
    }

    private List<IpAddress> parseForwardedChain(String header) {
        String[] values = header.split(",", -1);
        if (values.length > MAX_FORWARDED_HOPS) {
            return null;
        }
        List<IpAddress> addresses = new ArrayList<>(values.length);
        try {
            for (String value : values) {
                String candidate = value.strip();
                if (candidate.isEmpty()) {
                    return null;
                }
                addresses.add(IpAddress.parse(candidate));
            }
            return addresses;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isTrusted(IpAddress address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(address));
    }

    private record IpNetwork(byte[] networkAddress, int prefixLength) {

        private static IpNetwork parse(String cidr) {
            Objects.requireNonNull(cidr, "trusted proxy CIDR must not be null");
            String[] parts = cidr.split("/", -1);
            if (parts.length != 2 || parts[0].isBlank() || !parts[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Trusted proxy entries must use numeric CIDR notation");
            }
            IpAddress address = IpAddress.parse(parts[0]);
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix length", exception);
            }
            int bitLength = address.bytes().length * 8;
            if (prefix < 0 || prefix > bitLength) {
                throw new IllegalArgumentException("Trusted proxy prefix is outside the address range");
            }
            byte[] masked = address.bytes().clone();
            for (int bit = prefix; bit < bitLength; bit++) {
                masked[bit / 8] &= (byte) ~(1 << (7 - (bit % 8)));
            }
            return new IpNetwork(masked, prefix);
        }

        private boolean contains(IpAddress address) {
            byte[] candidate = address.bytes();
            if (candidate.length != networkAddress.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != networkAddress[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (networkAddress[fullBytes] & mask);
        }
    }

    private record IpAddress(byte[] bytes, String canonical) {

        private static IpAddress parse(String value) {
            if (value == null || value.isBlank() || !value.equals(value.strip())
                    || value.indexOf('%') >= 0 || value.startsWith("[") || value.endsWith("]")) {
                throw new IllegalArgumentException("Address must be a numeric IP literal");
            }
            try {
                InetAddress parsed;
                if (value.indexOf(':') >= 0) {
                    if (!value.matches("[0-9a-fA-F:.]+")) {
                        throw new IllegalArgumentException("Address must be a numeric IPv6 literal");
                    }
                    parsed = InetAddress.getByName(value);
                    if (!(parsed instanceof Inet6Address)) {
                        throw new IllegalArgumentException("Address must be IPv6");
                    }
                } else {
                    String[] octets = value.split("\\.", -1);
                    if (octets.length != 4) {
                        throw new IllegalArgumentException("Address must be a numeric IPv4 or IPv6 literal");
                    }
                    byte[] raw = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        if (!octets[i].matches("0|[1-9][0-9]{0,2}")) {
                            throw new IllegalArgumentException("Address must use canonical IPv4 octets");
                        }
                        int octet = Integer.parseInt(octets[i]);
                        if (octet > 255) {
                            throw new IllegalArgumentException("IPv4 octet exceeds 255");
                        }
                        raw[i] = (byte) octet;
                    }
                    parsed = InetAddress.getByAddress(raw);
                }
                return new IpAddress(parsed.getAddress(), parsed.getHostAddress().toLowerCase(java.util.Locale.ROOT));
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Address must be a valid numeric IP literal", exception);
            }
        }
    }
}
