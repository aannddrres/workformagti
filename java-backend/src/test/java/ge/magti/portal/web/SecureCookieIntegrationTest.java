package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.security.PortalSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ASVS V3.3.1, V3.3.2, V3.3.3 and V3.3.4 as production runs them: with
 * COOKIE_SECURE=true, which ProductionSafetyGuard makes mandatory. The
 * __Host- prefix is what stops a sibling host under the same parent domain
 * from planting its own session or CSRF cookie on the portal: the browser
 * accepts a __Host- cookie only if it is Secure, has Path=/ and no Domain,
 * which no other host can set.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "portal.security.cookie.secure=true")
class SecureCookieIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PortalSessionService sessionService;

    private static String setCookie(List<String> headers, String name) {
        return headers.stream().filter(header -> header.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + headers));
    }

    private static void assertHostOnlySecure(String header) {
        String attributes = header.toLowerCase(java.util.Locale.ROOT);
        assertTrue(attributes.contains("; secure"), header);
        assertTrue(attributes.contains("; path=/"), header);
        assertTrue(attributes.contains("; samesite=strict"), header);
        assertFalse(attributes.contains("; domain="), header);
    }

    @Test
    void theSessionCookieIsHostPrefixedSecureAndHttpOnly() throws Exception {
        List<String> headers = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@magti.ge\",\"password\":\"any\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders("Set-Cookie");

        String session = setCookie(headers, "__Host-access_token");
        assertHostOnlySecure(session);
        assertTrue(session.contains("; HttpOnly"), session);
        assertTrue(headers.stream().noneMatch(header -> header.startsWith("access_token=")), headers.toString());
    }

    /**
     * Spring adds this one as a servlet Cookie with a SameSite attribute,
     * which Tomcat writes out but MockMvc's Set-Cookie rendering drops, so
     * the cookie object is inspected rather than the header text.
     */
    @Test
    void theCsrfCookieIsHostPrefixedWhenSecure() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/not-a-real-route")
                        .header("Authorization", "Bearer " + jwtService.createAccessTokenFor(operator("secure-csrf@magti.ge"))))
                .andReturn().getResponse().getCookie("__Host-XSRF-TOKEN");

        assertNotNull(csrf, "no __Host-XSRF-TOKEN cookie");
        assertTrue(csrf.getSecure());
        assertEquals("/", csrf.getPath());
        assertEquals("strict", String.valueOf(csrf.getAttribute("SameSite")).toLowerCase(java.util.Locale.ROOT));
        assertNull(csrf.getDomain());
        assertFalse(csrf.isHttpOnly(), "Angular must be able to read it");
    }

    /** The unprefixed name is exactly what a planted cookie would use; it must not authenticate. */
    @Test
    void onlyTheHostPrefixedCookieAuthenticates() throws Exception {
        User user = operator("secure-session@magti.ge");
        String token = jwtService.createAccessTokenFor(user, sessionService.create(user, "10.0.0.8", "secure-test").getId());

        mockMvc.perform(get("/api/users/me").cookie(new Cookie("__Host-access_token", token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token)))
                .andExpect(status().isUnauthorized());
    }

    private User operator(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Secure Cookie Operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }
}
