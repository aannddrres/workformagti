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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityConfigIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PortalProperties properties;

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
