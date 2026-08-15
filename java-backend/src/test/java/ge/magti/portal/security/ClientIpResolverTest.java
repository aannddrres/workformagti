package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SEC-04/PR-04. These tests exist as much to pin what the resolver must
 * REFUSE to believe as what it should read: the whole point of the trusted-
 * proxy list is that turning on X-Forwarded-For handling unconditionally
 * (the usual `forward-headers-strategy=framework` one-liner) hands anyone
 * who can reach the app directly a spoofable rate-limit key and a forged
 * audit trail.
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolverTrusting(String... cidrs) {
        PortalProperties properties = new PortalProperties();
        properties.getSecurity().setTrustedProxies(List.of(cidrs));
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    /** The default. No trust configured means the header may as well not exist. */
    @Test
    void withNoTrustedProxiesTheHeaderIsIgnoredEntirely() {
        ClientIpResolver resolver = resolverTrusting();

        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "1.2.3.4")));
    }

    /**
     * The bug PR-04/SEC-04 describes: behind nginx every user resolved to the
     * proxy, so the rate limit was company-wide and the audit log recorded
     * the load balancer.
     */
    @Test
    void behindATrustedProxyTheRealClientIsRecovered() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        assertEquals("203.0.113.5", resolver.resolve(request("10.0.0.9", "203.0.113.5")));
    }

    /**
     * X-Forwarded-For is append-only and client-controlled, so the LEFTMOST
     * entry is whatever the caller typed. Taking it is the classic mistake --
     * it makes the header a free spoof of any address.
     */
    @Test
    void aForgedLeftmostEntryIsNotBelieved() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        String resolved = resolver.resolve(request("10.0.0.9", "9.9.9.9, 203.0.113.5"));

        assertEquals("203.0.113.5", resolved, "the rightmost untrusted hop is the closest one we can believe");
    }

    /** Chained proxies: skip our own hops, stop at the first address we did not vouch for. */
    @Test
    void trustedHopsInsideTheChainAreSkipped() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24", "10.1.0.0/24");

        assertEquals("203.0.113.5",
                resolver.resolve(request("10.0.0.9", "203.0.113.5, 10.1.0.4, 10.0.0.9")));
    }

    /** An untrusted peer's header is worthless no matter what it says. */
    @Test
    void anUntrustedPeerCannotSpoofItsOwnAddress() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        assertEquals("198.51.100.23",
                resolver.resolve(request("198.51.100.23", "127.0.0.1, 8.8.8.8")));
    }

    @Test
    void aTrustedProxyWithNoHeaderResolvesToTheProxyItself() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", null)));
    }

    /** Every hop trusted means no client we can name; the peer is the honest answer. */
    @Test
    void aChainOfOnlyTrustedHopsFallsBackToThePeer() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "10.0.0.3, 10.0.0.4")));
    }

    /**
     * The header is attacker input. A malformed hop must not throw out of the
     * login endpoint, and must not be mistaken for a trusted one.
     */
    @Test
    void garbageInTheHeaderDoesNotThrow() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/24");

        assertEquals("not-an-ip", resolver.resolve(request("10.0.0.9", "not-an-ip")));
    }

    /**
     * A typo in configuration must not take the application down, and must
     * fail CLOSED -- the unparseable entry simply stops being trusted.
     */
    @Test
    void anUnparseableTrustedProxyEntryIsSkippedNotFatal() {
        ClientIpResolver resolver = resolverTrusting("not-a-cidr", "10.0.0.0/24");

        assertEquals("203.0.113.5", resolver.resolve(request("10.0.0.9", "203.0.113.5")));
    }

    @Test
    void blankEntriesAreIgnoredSoAnEmptyEnvVarBehavesAsUnset() {
        ClientIpResolver resolver = resolverTrusting("", "   ");

        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "1.2.3.4")));
    }
}
