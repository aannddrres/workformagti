package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccessDiffControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository overrideRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createUser(String localPart, Role role) {
        User user = new User();
        user.setEmail(localPart + "@magti.ge");
        user.setName("Phase 9A " + localPart);
        user.setRole(role);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("CurrentPass1"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private void allowContentManage(User user) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(Permission.CONTENT_MANAGE.value());
        override.setState(UserPermissionOverride.State.ALLOW);
        override.setUpdatedAt(TbilisiTime.now());
        overrideRepository.saveAndFlush(override);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessTokenFor(user);
    }

    @Test
    void onlySystemAdminCanReadTheReportEvenWhenAnOperatorHasContentManage() throws Exception {
        for (Role role : List.of(Role.OPERATOR, Role.MANAGER, Role.CONTENT_ADMIN)) {
            User caller = createUser("p9a-gate-" + role.value(), role);
            if (role == Role.OPERATOR) {
                allowContentManage(caller);
            }

            mockMvc.perform(get("/api/admin/access-diff")
                            .header("Authorization", "Bearer " + tokenFor(caller)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/access-diff")).andExpect(status().isUnauthorized());

        User admin = createUser("p9a-gate-admin", Role.SYSTEM_ADMIN);
        mockMvc.perform(get("/api/admin/access-diff")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.users").isNumber())
                .andExpect(jsonPath("$.rows").isArray());
    }
}
