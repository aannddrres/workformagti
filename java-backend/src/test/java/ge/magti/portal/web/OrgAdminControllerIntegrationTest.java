package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrgAdminControllerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository permissionOverrideRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private LeadershipAssignmentRepository assignmentRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createUser(String localPart, Role role) {
        User user = new User();
        user.setEmail(localPart + "@magti.ge");
        user.setName("Phase 8 " + localPart);
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

    private Team createTeam(String suffix) {
        Department department = departmentRepository.findByStableKey("TECHNICAL").orElseThrow();
        Team team = new Team();
        team.setName("Phase 8 " + suffix);
        team.setStableKey("P8_" + suffix.toUpperCase().replace('-', '_'));
        team.setDepartmentId(department.getId());
        team.setCreatedAt(TbilisiTime.now());
        team.setActive(true);
        return teamRepository.saveAndFlush(team);
    }

    private void allowContentManage(User user) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(Permission.CONTENT_MANAGE.value());
        override.setState(UserPermissionOverride.State.ALLOW);
        override.setUpdatedAt(TbilisiTime.now());
        permissionOverrideRepository.saveAndFlush(override);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessTokenFor(user);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User user,
            JwtService jwtService) {
        return request.header("Authorization", "Bearer " + jwtService.createAccessTokenFor(user));
    }

    @Test
    void allFourEndpointsRequireTheSystemAdminRoleEvenWithContentManage() throws Exception {
        Team team = createTeam("gate");
        User target = createUser("p8-gate-target", Role.OPERATOR);
        for (Role role : List.of(Role.OPERATOR, Role.MANAGER, Role.CONTENT_ADMIN)) {
            User caller = createUser("p8-gate-" + role.value(), role);
            if (role == Role.OPERATOR) {
                allowContentManage(caller);
            }
            String token = tokenFor(caller);

            mockMvc.perform(get("/api/admin/org/structure").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/org/assignments").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/org/assignments")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"user_id\":" + target.getId() + ",\"team_id\":" + team.getId()
                                    + ",\"assignment_type\":\"ACTING\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/admin/org/assignments/999999")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(get("/api/admin/org/structure")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondActivePrimaryForTheSameTeamGetsAClearConflict() throws Exception {
        User admin = createUser("p8-primary-admin", Role.SYSTEM_ADMIN);
        User firstLeader = createUser("p8-primary-first", Role.MANAGER);
        User secondLeader = createUser("p8-primary-second", Role.MANAGER);
        Team team = createTeam("primary-conflict");

        mockMvc.perform(authed(post("/api/admin/org/assignments"), admin, jwtService)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + firstLeader.getId() + ",\"team_id\":" + team.getId()
                                + ",\"assignment_type\":\"PRIMARY\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/admin/org/assignments"), admin, jwtService)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + secondLeader.getId() + ",\"team_id\":" + team.getId()
                                + ",\"assignment_type\":\"PRIMARY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PRIMARY")));

        assertEquals(1, assignmentRepository.findByTeamIdAndActiveTrue(team.getId()).size());
    }

    @Test
    void deactivationKeepsHistoryAndBothMutationsAreAuditedWithTheActor() throws Exception {
        User admin = createUser("p8-audit-admin", Role.SYSTEM_ADMIN);
        User leader = createUser("p8-audit-leader", Role.MANAGER);
        Team team = createTeam("audit-history");

        String createdBody = mockMvc.perform(authed(post("/api/admin/org/assignments"), admin, jwtService)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + leader.getId() + ",\"team_id\":" + team.getId()
                                + ",\"assignment_type\":\"ACTING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value(leader.getName()))
                .andReturn().getResponse().getContentAsString();
        long assignmentId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createdBody).get("id").asLong();

        mockMvc.perform(authed(delete("/api/admin/org/assignments/" + assignmentId), admin, jwtService))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_active").value(false));

        LeadershipAssignment stored = assignmentRepository.findById(assignmentId).orElseThrow();
        assertFalse(stored.isActive());
        assertNotNull(stored.getEndedAt());

        List<AuditLog> auditRows = auditLogRepository.findAll().stream()
                .filter(row -> "leadership_assignment".equals(row.getItemType()))
                .filter(row -> assignmentId == row.getItemId())
                .toList();
        assertTrue(auditRows.stream().map(AuditLog::getAction).toList().containsAll(
                List.of("CREATE_LEADERSHIP_ASSIGNMENT", "DEACTIVATE_LEADERSHIP_ASSIGNMENT")));
        assertTrue(auditRows.stream().allMatch(row -> admin.getId().equals(row.getAdminId())));
        assertTrue(auditRows.stream().allMatch(row -> admin.getName().equals(row.getAdminNameSnapshot())));
        assertTrue(auditRows.stream().allMatch(row -> row.getItemNameSnapshot().contains(leader.getName())));

        AuditLog createdRow = auditRows.stream()
                .filter(row -> "CREATE_LEADERSHIP_ASSIGNMENT".equals(row.getAction()))
                .findFirst().orElseThrow();
        JsonNode created = objectMapper.readTree(createdRow.getDetails());
        assertEquals(1, created.path("schema_version").asInt());
        assertEquals("SUCCESS", created.path("result").asText());
        assertTrue(created.path("before").isNull());
        assertTrue(created.path("after").path("active").asBoolean());
        assertEquals("ACTING", created.path("after").path("assignment_type").asText());
        assertEquals(team.getId(), created.path("after").path("team_id").asLong());

        AuditLog deactivatedRow = auditRows.stream()
                .filter(row -> "DEACTIVATE_LEADERSHIP_ASSIGNMENT".equals(row.getAction()))
                .findFirst().orElseThrow();
        JsonNode deactivated = objectMapper.readTree(deactivatedRow.getDetails());
        assertTrue(deactivated.path("before").path("active").asBoolean());
        assertFalse(deactivated.path("after").path("active").asBoolean());
        assertFalse(deactivated.path("after").path("ended_at").isNull());
    }
}
