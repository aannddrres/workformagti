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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Locks D-7: authenticated callers cannot reach the removed private-messaging API. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RemovedMessagingRoutesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void everyFormerPrivateMessagingEndpointIsGone() throws Exception {
        User admin = createSystemAdmin();
        String token = jwtService.createAccessToken(Map.of(
                "sub", admin.getEmail(), "role", admin.getRole().value()));

        assertGone(authed(get("/api/messages"), token));
        assertGone(authed(get("/api/messages/sent"), token));
        assertGone(authed(post("/api/messages"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":1,\"content\":\"removed\"}"));
        assertGone(authed(post("/api/messages/1/read"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertGone(authed(delete("/api/messages/1"), token));
    }

    private User createSystemAdmin() {
        User user = new User();
        user.setEmail("removed-messaging-admin@magti.ge");
        user.setName("Removed messaging admin");
        user.setRole(Role.SYSTEM_ADMIN);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.SYSTEM_ADMIN).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private static MockHttpServletRequestBuilder authed(
            MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private void assertGone(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isNotFound());
    }
}
