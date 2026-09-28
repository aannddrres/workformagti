package ge.magti.portal.security;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs on a freshly built context, not the cached one other classes share.
 * spring-security-test's {@code csrf()} request post-processor, which
 * AuthControllerIntegrationTest, UploadControllerIntegrationTest and
 * DenyByDefaultIntegrationTest use, swaps the CsrfFilter's token repository
 * for a session-backed test double -- in the filter chain itself, for the
 * rest of that context's life. Every class after them then saw no
 * XSRF-TOKEN cookie at all, and the two cookie tests below failed whenever
 * one of those ran first: CI's java-integration job on main, 2026-09-21
 * onwards. These tests are about the real repository, so they must meet it.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SecurityConfigIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PortalProperties properties;
    @Autowired private PortalSessionService sessionService;

    private User operator(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Security Boundary Operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("CurrentPass1"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    @Test
    void theCsrfCookieExpiresWithTheSessionItProtects() throws Exception {
        String token = jwtService.createAccessTokenFor(operator("csrf-lifetime-operator@magti.ge"));

        MvcResult result = mockMvc.perform(get("/api/not-a-real-route")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        Cookie issued = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(issued, "the first request should establish a CSRF cookie");
        long sessionSeconds = Duration
                .ofMinutes(properties.getSecurity().getJwt().getAccessTokenExpireMinutes())
                .toSeconds();
        assertEquals(sessionSeconds, issued.getMaxAge(),
                "the CSRF cookie must last exactly as long as the access token it guards: a browser "
                        + "restart used to come back signed in with no CSRF token, and the first POST "
                        + "the app makes had no header to send");
    }

    @Test
    void anAuthenticatedRequestDoesNotReissueTheCsrfCookie() throws Exception {
        String token = jwtService.createAccessTokenFor(operator("csrf-rotation-operator@magti.ge"));

        MvcResult first = mockMvc.perform(get("/api/not-a-real-route")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        Cookie issued = first.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(issued, "the first request should establish a CSRF cookie");

        MvcResult second = mockMvc.perform(get("/api/not-a-real-route")
                        .header("Authorization", "Bearer " + token)
                        .cookie(issued))
                .andReturn();

        assertNull(second.getResponse().getCookie("XSRF-TOKEN"),
                "a request that already carries a valid CSRF cookie must not be handed a new one: "
                        + "Angular copies the cookie into the header when it builds a request and the "
                        + "browser attaches the cookie moments later, so rotating it under a page's "
                        + "parallel requests leaves a POST's header behind its own cookie");
    }

    /**
     * ASVS V3.5.1. A cookie is attached by the browser to any request, so a
     * cookie-authenticated change must also prove it came from the page: the
     * double-submitted token. Without it, refused; with it, accepted.
     */
    @Test
    void aStateChangingRequestWithoutTheCsrfTokenIsRefused() throws Exception {
        User user = operator("csrf-enforced-operator@magti.ge");
        String token = jwtService.createAccessTokenFor(user, sessionService.create(user, "10.0.0.9", "csrf-test").getId());
        Cookie session = new Cookie(properties.getSecurity().getCookie().sessionCookieName(), token);

        mockMvc.perform(post("/api/auth/session/heartbeat").cookie(session))
                .andExpect(status().isForbidden());

        Cookie csrf = mockMvc.perform(get("/api/users/me").cookie(session)).andReturn().getResponse()
                .getCookie(properties.getSecurity().getCookie().csrfCookieName());
        assertNotNull(csrf, "the page's first request establishes the token");
        mockMvc.perform(post("/api/auth/session/heartbeat").cookie(session, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().is2xxSuccessful());
    }

    /** ASVS V11.5.1: 256 bits from SecureRandom, not a UUID's 122. */
    @Test
    void theCsrfTokenCarries256RandomBits() throws Exception {
        String token = jwtService.createAccessTokenFor(operator("csrf-entropy-operator@magti.ge"));
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (int i = 0; i < 5; i++) {
            Cookie issued = mockMvc.perform(get("/api/not-a-real-route").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getCookie(properties.getSecurity().getCookie().csrfCookieName());
            assertNotNull(issued);
            byte[] raw = java.util.Base64.getUrlDecoder().decode(issued.getValue());
            assertEquals(32, raw.length, "token " + issued.getValue());
            seen.add(issued.getValue());
        }
        assertEquals(5, seen.size(), "every fresh token differs");
    }

    @Test
    void unauthenticatedRequestsAreDeniedBeforeUnknownRoutesReachMvc() throws Exception {
        mockMvc.perform(get("/api/not-a-real-route"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void authenticatedRequestsPassTheGlobalBoundaryAndReachMvcRouting() throws Exception {
        User user = new User();
        user.setEmail("security-boundary-operator@magti.ge");
        user.setName("Security Boundary Operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("CurrentPass1"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        user = userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/not-a-real-route")
                        .header("Authorization", "Bearer " + jwtService.createAccessTokenFor(user)))
                .andExpect(status().isNotFound());
    }
}
