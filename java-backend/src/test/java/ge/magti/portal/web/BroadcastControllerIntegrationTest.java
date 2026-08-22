package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.BroadcastPriority;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.BroadcastAnnouncementRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.MessageRepository;
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

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Oracle and HTTP coverage for the independent Broadcast lifecycle. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BroadcastControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired LeadershipAssignmentRepository leadershipRepository;
    @Autowired UserPermissionOverrideRepository overrideRepository;
    @Autowired BroadcastAnnouncementRepository broadcastRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private User createUser(String prefix, Role role) {
        User user = new User();
        user.setEmail(prefix + "-" + System.nanoTime() + "@magti.ge");
        user.setName("ტესტ მომხმარებელი " + prefix);
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream().map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String requestJson(String message, String priority, OffsetDateTime endsAt) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "message", message, "priority", priority, "ends_at", endsAt.toString()));
    }

    private void grant(User user, Permission permission, User actor) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(permission.value());
        override.setState(UserPermissionOverride.State.ALLOW);
        override.setUpdatedAt(TbilisiTime.now());
        override.setUpdatedBy(actor.getId());
        overrideRepository.saveAndFlush(override);
    }

    private void assignTeamLeadership(User user, AssignmentType type, boolean active) {
        Team team = new Team();
        team.setName("Broadcast group " + System.nanoTime());
        team.setCreatedAt(TbilisiTime.now());
        team.setActive(true);
        team = teamRepository.saveAndFlush(team);

        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(user.getId());
        assignment.setTeamId(team.getId());
        assignment.setAssignmentType(type);
        assignment.setActive(active);
        assignment.setStartedAt(TbilisiTime.now());
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        if (!active) assignment.setEndedAt(TbilisiTime.now());
        leadershipRepository.saveAndFlush(assignment);
    }

    @Test
    void activeListRequiresAuthenticationButEveryAuthenticatedEmployeeCanReadIt() throws Exception {
        User publisher = createUser("broadcast-reader-publisher", Role.SYSTEM_ADMIN);
        User operator = createUser("broadcast-reader-operator", Role.OPERATOR);
        mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(publisher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ყველასთვის ხილული", "NORMAL", TbilisiTime.now().plusHours(2))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/broadcasts")).andExpect(status().isUnauthorized());
        mockMvc.perform(authed(get("/api/broadcasts"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("ყველასთვის ხილული"))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].can_end_early").value(false));
    }

    @Test
    void plainOperatorAndRoleOnlyManagerCannotPublishOrReadHistory() throws Exception {
        User operator = createUser("broadcast-denied-op", Role.OPERATOR);
        User manager = createUser("broadcast-denied-manager", Role.MANAGER);
        String body = requestJson("არ უნდა შეიქმნას", "IMPORTANT", TbilisiTime.now().plusHours(1));

        for (User user : new User[]{operator, manager}) {
            mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(user))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(authed(get("/api/broadcasts/history"), tokenFor(user)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void explicitContentManagerAndBothKindsOfActiveGroupLeaderCanPublish() throws Exception {
        User sysadmin = createUser("broadcast-grant-admin", Role.SYSTEM_ADMIN);
        User contentManager = createUser("broadcast-granted-content", Role.OPERATOR);
        grant(contentManager, Permission.CONTENT_MANAGE, sysadmin);
        User primaryLeader = createUser("broadcast-primary", Role.OPERATOR);
        assignTeamLeadership(primaryLeader, AssignmentType.PRIMARY, true);
        User actingLeader = createUser("broadcast-acting", Role.OPERATOR);
        assignTeamLeadership(actingLeader, AssignmentType.ACTING, true);

        for (User user : new User[]{contentManager, primaryLeader, actingLeader, sysadmin}) {
            mockMvc.perform(authed(get("/api/me/effective-access"), tokenFor(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.can_publish_announcement").value(true));
            mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson("ავტორი: " + user.getName(), "NORMAL", TbilisiTime.now().plusHours(1))))
                .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.publisher_name").value(user.getName()))
                    .andExpect(jsonPath("$.can_end_early").value(true));
        }
    }

    @Test
    void inactiveLeadershipDoesNotGrantPublishing() throws Exception {
        User formerLeader = createUser("broadcast-former-leader", Role.OPERATOR);
        assignTeamLeadership(formerLeader, AssignmentType.ACTING, false);

        mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(formerLeader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("უარყოფილი", "NORMAL", TbilisiTime.now().plusHours(1))))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestRequiresTextPriorityAndAFutureEndAndHasNoTargetingContract() throws Exception {
        User admin = createUser("broadcast-validation", Role.SYSTEM_ADMIN);
        String token = tokenFor(admin);

        mockMvc.perform(authed(post("/api/broadcasts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(" ", "NORMAL", TbilisiTime.now().plusHours(1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/broadcasts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("წარსული", "CRITICAL", TbilisiTime.now().minusMinutes(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("დასრულების დრო მომავალში უნდა იყოს"));

        String targeted = objectMapper.writeValueAsString(Map.of(
                "message", "მიზნობრივი არ შეიძლება", "priority", "NORMAL",
                "ends_at", TbilisiTime.now().plusHours(1).toString(), "target_department", "ოფისი"));
        mockMvc.perform(authed(post("/api/broadcasts"), token).contentType(MediaType.APPLICATION_JSON).content(targeted))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishCreatesOneBroadcastNoRecipientMessagesAndACompleteAuditSnapshot() throws Exception {
        User admin = createUser("broadcast-audit", Role.SYSTEM_ADMIN);
        long messageCount = messageRepository.count();

        String body = mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("  ოფისი დროებით დაკეტილია  ", "CRITICAL", TbilisiTime.now().plusHours(3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("ოფისი დროებით დაკეტილია"))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();
        assertEquals(messageCount, messageRepository.count(), "broadcast must never fan out into personal messages");
        var audit = auditLogRepository.findAll().stream()
                .filter(row -> "PUBLISH_BROADCAST".equals(row.getAction()) && id == row.getItemId())
                .findFirst().orElseThrow();
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("ALL_AUTHENTICATED", details.get("audience").asText());
        assertEquals("CRITICAL", details.get("priority").asText());
        assertEquals("ოფისი დროებით დაკეტილია", details.get("message").asText());
        assertEquals(admin.getName(), audit.getAdminNameSnapshot());
    }

    @Test
    void expiredAndEarlyEndedRowsDisappearFromActiveButRemainInHistory() throws Exception {
        User admin = createUser("broadcast-history", Role.SYSTEM_ADMIN);
        String activeBody = mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ადრე მოსახსნელი", "IMPORTANT", TbilisiTime.now().plusHours(2))))
                .andReturn().getResponse().getContentAsString();
        long activeId = objectMapper.readTree(activeBody).get("id").asLong();
        mockMvc.perform(authed(post("/api/broadcasts/" + activeId + "/end"), tokenFor(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ended"));

        BroadcastAnnouncement expired = new BroadcastAnnouncement();
        expired.setMessage("ვადაგასული");
        expired.setPriority(BroadcastPriority.NORMAL);
        expired.setPublishedAt(TbilisiTime.now().minusHours(2));
        expired.setEndsAt(TbilisiTime.now().minusHours(1));
        expired.setPublishedByUserId(admin.getId());
        expired.setPublisherNameSnapshot(admin.getName());
        broadcastRepository.saveAndFlush(expired);

        String activeList = mockMvc.perform(authed(get("/api/broadcasts"), tokenFor(admin)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(objectMapper.readTree(activeList).isEmpty());

        String history = mockMvc.perform(authed(get("/api/broadcasts/history"), tokenFor(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_items").value(2))
                .andReturn().getResponse().getContentAsString();
        String statuses = objectMapper.readTree(history).get("items").toString();
        assertTrue(statuses.contains("ended"));
        assertTrue(statuses.contains("expired"));
    }

    @Test
    void onlyPublisherOrSystemAdminCanEndEarlyAndLockAdvancesExactlyOnce() throws Exception {
        User publisher = createUser("broadcast-owner", Role.OPERATOR);
        User sysadmin = createUser("broadcast-owner-admin", Role.SYSTEM_ADMIN);
        grant(publisher, Permission.CONTENT_MANAGE, sysadmin);
        User otherPublisher = createUser("broadcast-other-publisher", Role.OPERATOR);
        grant(otherPublisher, Permission.CONTENT_MANAGE, sysadmin);

        String created = mockMvc.perform(authed(post("/api/broadcasts"), tokenFor(publisher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("მოსახსნელი", "NORMAL", TbilisiTime.now().plusHours(1))))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();
        long lockBefore = broadcastRepository.findById(id).orElseThrow().getLockVersion();

        mockMvc.perform(authed(post("/api/broadcasts/" + id + "/end"), tokenFor(otherPublisher)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/broadcasts/" + id + "/end"), tokenFor(sysadmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lock_version").value(lockBefore + 1));

        BroadcastAnnouncement ended = broadcastRepository.findById(id).orElseThrow();
        assertEquals(lockBefore + 1, ended.getLockVersion());
        assertEquals(sysadmin.getName(), ended.getEndedByNameSnapshot());
        mockMvc.perform(authed(post("/api/broadcasts/" + id + "/end"), tokenFor(sysadmin)))
                .andExpect(status().isConflict());
    }

    @Test
    void historyPaginationIsBounded() throws Exception {
        User admin = createUser("broadcast-pagination", Role.SYSTEM_ADMIN);
        mockMvc.perform(authed(get("/api/broadcasts/history?page=-1"), tokenFor(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(get("/api/broadcasts/history?size=101"), tokenFor(admin)))
                .andExpect(status().isBadRequest());
    }
}
