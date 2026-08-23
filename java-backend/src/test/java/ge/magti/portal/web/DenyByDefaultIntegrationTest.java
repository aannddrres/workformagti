package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HTTP proof of the floor {@code SecurityConfig} puts under the per-endpoint
 * guards.
 *
 * <p>{@link ge.magti.portal.security.AnonymousSurfaceTest} checks that the
 * allowlist and the access contract agree; this checks what a caller actually
 * gets. The case worth writing a test for is the one no static check can
 * reach: an endpoint whose author simply forgot the guard. So this test
 * registers one -- {@link Unguarded} has no {@code require*} call of any kind
 * -- and asserts an anonymous caller still cannot read it.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DenyByDefaultIntegrationTest {

    /** Exactly the mistake the default protects against, in one line. */
    @RestController
    static class Unguarded {
        @GetMapping("/api/test-only/unguarded")
        Map<String, String> read() {
            return Map.of("secret", "would have been served to anyone");
        }
    }

    @TestConfiguration
    static class Registration {
        @Bean
        Unguarded unguarded() {
            return new Unguarded();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String tokenForAnOperator() {
        User user = new User();
        user.setEmail("deny-by-default@magti.ge");
        user.setName("Deny by default");
        user.setRole(Role.OPERATOR);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user = userRepository.saveAndFlush(user);
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    @Test
    void anEndpointThatForgotItsGuardIsStillClosedToAnonymousCallers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test-only/unguarded")).andReturn();

        assertEquals(401, result.getResponse().getStatus(),
                "an endpoint with no require*() call must not be readable without a token");
        assertTrue(!result.getResponse().getContentAsString().contains("would have been served"),
                "the handler ran and its body reached the caller");
    }

    /** The floor is only about having a token; it never invents a capability. */
    @Test
    void theSameEndpointAnswersOnceAnyValidTokenIsPresented() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test-only/unguarded")
                        .header("Authorization", "Bearer " + tokenForAnOperator()))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void theDenialLooksLikeEveryOtherDenialTheFrontendHandles() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/articles")).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("{\"detail\":\"Could not validate credentials\"}",
                result.getResponse().getContentAsString(),
                "the frontend reads `detail` and cannot tell which layer answered; Spring's own entry point would "
                        + "have sent an empty 403 instead");
    }

    @Test
    void aTokenThatDoesNotValidateIsTreatedAsNoTokenAtAll() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/articles")
                        .header("Authorization", "Bearer not.a.real.token"))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
    }

    @Test
    void healthAnswersBeforeAnyoneCanPossiblyHaveAToken() throws Exception {
        assertEquals(200, mockMvc.perform(get("/api/health")).andReturn().getResponse().getStatus());
    }

    @Test
    void logoutStillSucceedsWithoutOne() throws Exception {
        assertEquals(200, mockMvc.perform(post("/api/auth/logout")).andReturn().getResponse().getStatus());
    }

    /**
     * Spring forwards a failed request to /error to render the body. If that
     * forward were authorized like the original request, an authenticated
     * caller's 404 would come back as 401 -- a wrong status for a request
     * whose authorization had already passed.
     */
    @Test
    void anAuthenticatedRequestForSomethingMissingKeepsItsOwnStatus() throws Exception {
        int status = mockMvc.perform(get("/api/no-such-endpoint")
                        .header("Authorization", "Bearer " + tokenForAnOperator()))
                .andReturn().getResponse().getStatus();

        assertNotEquals(401, status, "the /error forward was denied and replaced the real status");
        assertEquals(404, status);
    }

    /** And an anonymous caller learns nothing about which paths exist. */
    @Test
    void anAnonymousRequestForSomethingMissingIsDeniedRatherThanAnswered() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/no-such-endpoint")).andReturn().getResponse().getStatus());
    }
}
