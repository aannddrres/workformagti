package ge.magti.portal.docs;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProxyDeploymentContractTest {
    @Test
    void documentedTwoHopExamplesRecoverDistinctClientsAndRejectSpoofing() throws Exception {
        for (String path : List.of("java-backend/src/main/resources/application.yml", "k8s/README_KA.md")) {
            var examples = Pattern.compile("TRUSTED_PROXIES=([0-9.,/]+)")
                    .matcher(Files.readString(RepoRoot.path(path))).results().map(match -> match.group(1)).toList();
            assertFalse(examples.isEmpty(), path + " needs a synthetic two-hop example");
            for (String example : examples) {
                var properties = new PortalProperties();
                properties.getSecurity().setTrustedProxies(Arrays.asList(example.split(",")));
                var resolver = new ClientIpResolver(properties);
                for (String client : List.of("203.0.113.5", "203.0.113.6")) {
                    assertEquals(client, resolver.resolve(request("10.42.0.7", "198.51.100.99, " + client + ", 10.43.0.4")),
                            path + " example must cover the frontend socket peer and ingress hop");
                }
                assertEquals("192.0.2.8", resolver.resolve(request("192.0.2.8", "203.0.113.5, 10.43.0.4")),
                        "an unrelated direct peer must not supply a trusted forwarding chain");
            }
        }
    }

    @Test
    void trustingOnlyIngressReproducesTheLostClientAddress() {
        var properties = new PortalProperties();
        properties.getSecurity().setTrustedProxies(List.of("10.43.0.4"));
        assertEquals("10.42.0.7", new ClientIpResolver(properties)
                .resolve(request("10.42.0.7", "203.0.113.5, 10.43.0.4")));
    }

    private static MockHttpServletRequest request(String peer, String chain) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", chain);
        return request;
    }
}
