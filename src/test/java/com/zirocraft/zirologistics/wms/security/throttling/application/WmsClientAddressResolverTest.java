package com.zirocraft.zirologistics.wms.security.throttling.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderWhenSocketPeerIsNotTrusted() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));

        assertEquals("198.51.100.10", resolver.resolve("198.51.100.10", "203.0.113.8"));
    }

    @Test
    void resolvesClientAddressFromTrustedProxy() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));

        assertEquals("203.0.113.8", resolver.resolve("10.20.1.5", "203.0.113.8"));
    }

    @Test
    void walksChainFromRightAndStopsAtFirstUntrustedHop() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16", "192.0.2.0/24"));

        assertEquals("203.0.113.8",
                resolver.resolve("10.20.1.5", "203.0.113.8, 192.0.2.9"));
    }

    @Test
    void doesNotTrustSpoofedLeftmostValuePastAnUntrustedProxy() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));

        assertEquals("192.0.2.9",
                resolver.resolve("10.20.1.5", "203.0.113.8, 192.0.2.9"));
    }

    @Test
    void fallsBackToSocketPeerWhenForwardedHeaderIsMalformed() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));

        assertEquals("10.20.1.5", resolver.resolve("10.20.1.5", "203.0.113.8, unknown"));
        assertEquals("10.20.1.5", resolver.resolve("10.20.1.5", "203.0.113.8,,192.0.2.9"));
    }

    @Test
    void fallsBackWhenForwardedChainExceedsHopLimit() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));
        String header = String.join(",", java.util.Collections.nCopies(17, "203.0.113.8"));

        assertEquals("10.20.1.5", resolver.resolve("10.20.1.5", header));
    }

    @Test
    void supportsIpv6TrustedProxyNetworks() {
        var resolver = new WmsClientAddressResolver(List.of("2001:db8:abcd::/48"));

        assertEquals("2001:db8:ffff:0:0:0:0:1",
                resolver.resolve("2001:db8:abcd::10", "2001:db8:ffff::1"));
    }

    @Test
    void rejectsInvalidTrustedProxyConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new WmsClientAddressResolver(List.of("proxy.internal/24")));
        assertThrows(IllegalArgumentException.class,
                () -> new WmsClientAddressResolver(List.of("10.0.0.0/33")));
        assertThrows(IllegalArgumentException.class,
                () -> new WmsClientAddressResolver(List.of("10.0.0.0")));
    }

    @Test
    void rejectsInvalidSocketPeerAddress() {
        var resolver = new WmsClientAddressResolver(List.of("10.20.0.0/16"));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("client.example.com", "203.0.113.8"));
    }
}
