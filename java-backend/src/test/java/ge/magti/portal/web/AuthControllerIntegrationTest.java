package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hits POST /api/auth/login and /api/auth/logout through the real Spring
 * MVC + Security filter chain, against the real Oracle instance -- proves
 * the whole path (JIT provisioning -&gt; JWT issuance -&gt; cookie -&gt; audit
 * row) actually works end-to-end, not just that each piece compiles.
 * {@code @Transactional} rolls back the JIT-provisioned user and audit
 * rows afterward.
 *
 * <p>Each test uses its own fake remote address ({@link #withIp}) --
 * {@link ge.magti.portal.security.LoginRateLimiter} is a real singleton
 * shared across every test in this class (one Spring context), so without
 * distinct IPs, the rate-limit test would consume attempts other tests
 * need, and test order would silently start mattering.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void loginWithKnownTestEmailIssuesTokenAndSetsCookie() throws Exception {
        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().httpOnly("access_token", true));
    }

    @Test
    void loginWithUnknownEmailIsRejected() throws Exception {
        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody.real@magti.ge\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0));
    }

    @Test
    void eleventhLoginAttemptWithinAMinuteIsRateLimited() throws Exception {
        String body = "{\"email\":\"nobody.real@magti.ge\",\"password\":\"whatever\"}";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
