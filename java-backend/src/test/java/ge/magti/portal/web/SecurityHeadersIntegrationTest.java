package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * ASVS V3.2.1, V3.4.2, V3.4.4, V3.4.6, V14.2.2 and V14.3.2 for everything
 * the backend answers. nginx adds its own headers to the pages and assets it
 * serves (e2e/nginx-headers.spec.ts); for /api/ and /uploads/ it adds none,
 * so what Spring sends is what the browser gets.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityHeadersIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String operatorToken() {
        User user = new User();
        user.setEmail("security-headers-operator@magti.ge");
        user.setName("Security Headers Operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return jwtService.createAccessTokenFor(userRepository.saveAndFlush(user));
    }

    private static void assertHardened(MockHttpServletResponse response, String what) {
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"), what);
        assertEquals("DENY", response.getHeader("X-Frame-Options"), what);
        String csp = response.getHeader("Content-Security-Policy");
        assertNotNull(csp, what + ": no Content-Security-Policy");
        assertTrue(csp.contains("frame-ancestors 'none'"), what + ": " + csp);
        String cache = response.getHeader("Cache-Control");
        assertNotNull(cache, what + ": no Cache-Control");
        assertTrue(cache.contains("no-store"), what + ": " + cache);
    }

    @Test
    void apiResponsesCannotBeSniffedFramedOrCached() throws Exception {
        String token = operatorToken();

        assertHardened(mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse(), "an authenticated answer");
        assertHardened(mockMvc.perform(get("/api/articles/999999999").header("Authorization", "Bearer " + token))
                .andReturn().getResponse(), "an error answer");
        assertHardened(mockMvc.perform(get("/api/users/me")).andReturn().getResponse(), "a refusal");
    }

    /** No CORS configuration exists, so a foreign origin is granted nothing, preflight or not. */
    @Test
    void aCrossOriginRequestGetsNoCorsGrant() throws Exception {
        String token = operatorToken();

        MockHttpServletResponse simple = mockMvc.perform(get("/api/users/me")
                        .header("Origin", "https://attacker.example")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertNull(simple.getHeader("Access-Control-Allow-Origin"));
        assertNull(simple.getHeader("Access-Control-Allow-Credentials"));

        MockHttpServletResponse preflight = mockMvc.perform(options("/api/favorites")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andReturn().getResponse();
        assertNull(preflight.getHeader("Access-Control-Allow-Origin"));
        assertNull(preflight.getHeader("Access-Control-Allow-Methods"));
    }
}
