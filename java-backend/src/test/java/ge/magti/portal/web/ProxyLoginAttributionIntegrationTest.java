package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest(properties = "portal.security.trusted-proxies=10.42.0.7,10.43.0.4")
@AutoConfigureMockMvc
@Transactional
class ProxyLoginAttributionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private AuditLogRepository audits;
    @Autowired private UserRepository users;

    @Test
    void twoClientsHaveDistinctAuditAddressesAndThrottleBudgetsThroughBothProxyHops() throws Exception {
        String email = "test_operator_proxy_" + System.nanoTime() + "@magti.ge";
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(email, "10.42.0.7", "198.51.100.99, 203.0.113.51, 10.43.0.4"))
                    .andExpect(status().isOk());
        }
        mvc.perform(login(email, "10.42.0.7", "198.51.100.88, 203.0.113.51, 10.43.0.4"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(login(email, "10.42.0.7", "198.51.100.99, 203.0.113.52, 10.43.0.4"))
                .andExpect(status().isOk());
        mvc.perform(login(email, "192.0.2.71", "203.0.113.51, 10.43.0.4"))
                .andExpect(status().isOk());

        Long userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        var logins = audits.findAll().stream()
                .filter(row -> "LOGIN".equals(row.getAction()) && userId.equals(row.getItemId())).toList();
        assertEquals(12, logins.size());
        assertEquals(Set.of("203.0.113.51", "203.0.113.52", "192.0.2.71"),
                logins.stream().map(row -> row.getIpAddress()).collect(Collectors.toSet()));
    }

    private static MockHttpServletRequestBuilder login(String email, String peer, String chain) {
        return post("/api/auth/login").with(request -> {
            request.setRemoteAddr(peer);
            return request;
        }).header("X-Forwarded-For", chain).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"fixture\"}");
    }
}
