package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.reminder.ReminderService;
import ge.magti.portal.reminder.ReminderSweepService;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReminderControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired LeadershipAssignmentRepository leadershipRepository;
    @Autowired RequiredReadingRepository readingRepository;
    @Autowired ReadStatusRepository readStatusRepository;
    @Autowired ReminderRepository reminderRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ReminderService reminderService;
    @Autowired ReminderSweepService sweepService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void inboxAndReadStateAreIsolatedPerCallerAndReadIsIdempotent() throws Exception {
        User creator = user("reminder-owner-creator", Role.CONTENT_ADMIN, uniqueDepartment("owner"), null);
        User owner = user("reminder-owner", Role.OPERATOR, creator.getDepartment(), null);
        User other = user("reminder-other", Role.OPERATOR, creator.getDepartment(), null);
        RequiredReading reading = reading(creator.getDepartment(), TbilisiTime.now().plusDays(3));
        reminderService.deliverAssignment(reading, creator);
        long reminderId = reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                owner.getId(), PageRequest.of(0, 1_000)).getContent().get(0).getId();
        long otherReminderId = reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                other.getId(), PageRequest.of(0, 1_000)).getContent().get(0).getId();
        assertTrue(reminderId != otherReminderId, "each recipient must have a separate reminder row");

        mockMvc.perform(get("/api/reminders")).andExpect(status().isUnauthorized());
        mockMvc.perform(authed(get("/api/reminders?page=-1"), tokenFor(owner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(get("/api/reminders"), tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.items[0].id").value((int) reminderId))
                .andExpect(jsonPath("$.items[0].type").value("ASSIGNMENT"))
                .andExpect(jsonPath("$.items[0].content").value(org.hamcrest.Matchers.containsString("მასალა #")));

        mockMvc.perform(authed(get("/api/reminders"), tokenFor(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.items[0].id").value((int) otherReminderId));

        mockMvc.perform(authed(post("/api/reminders/" + reminderId + "/read"), tokenFor(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/reminders/" + reminderId + "/read"), tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lock_version").value(1));
        assertNull(reminderRepository.findById(otherReminderId).orElseThrow().getReadAt(),
                "reading one caller's reminder must not change another caller's reminder");
        mockMvc.perform(authed(post("/api/reminders/" + reminderId + "/read"), tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lock_version").value(1));

        long readAudits = auditLogRepository.findAll().stream()
                .filter(row -> "READ_REMINDER".equals(row.getAction()) && row.getItemId().equals(reminderId)).count();
        assertEquals(1, readAudits, "an idempotent second read must not create a second audit event");
        var readAudit = auditLogRepository.findAll().stream()
                .filter(row -> "READ_REMINDER".equals(row.getAction()) && row.getItemId().equals(reminderId))
                .findFirst().orElseThrow();
        JsonNode readDetails = objectMapper.readTree(readAudit.getDetails());
        assertEquals("SUCCESS", readDetails.path("result").asText());
        assertTrue(readDetails.path("before").path("read_at").isNull());
        assertFalse(readDetails.path("after").path("read_at").isNull());
    }

    @Test
    void dueSoonAndOverdueSweepsDeliverExactlyOnceAndSkipCompletedRecipient() throws Exception {
        String department = uniqueDepartment("schedule");
        User creator = user("reminder-schedule-creator", Role.CONTENT_ADMIN, department, null);
        User pending = user("reminder-schedule-pending", Role.OPERATOR, department, null);
        User completed = user("reminder-schedule-complete", Role.OPERATOR, department, null);
        RequiredReading reading = reading(department, TbilisiTime.now().plusHours(2));
        reminderService.deliverAssignment(reading, creator);

        assertEquals(2, sweepService.runOnce());
        assertEquals(0, sweepService.runOnce());
        assertEquals(1, reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                        pending.getId(), PageRequest.of(0, 1_000)).stream()
                .filter(value -> value.getType() == ReminderType.DUE_SOON).count());

        ReadStatus done = new ReadStatus();
        done.setUserId(completed.getId());
        done.setRequiredReadingId(reading.getId());
        done.setStatus("read");
        done.setReadAt(TbilisiTime.now());
        readStatusRepository.saveAndFlush(done);
        reading.setDueDate(TbilisiTime.now().minusMinutes(1));
        readingRepository.saveAndFlush(reading);

        assertEquals(1, sweepService.runOnce());
        assertEquals(0, sweepService.runOnce());
        assertTrue(reminderRepository.existsByRequiredReadingIdAndRecipientUserIdAndType(
                reading.getId(), pending.getId(), ReminderType.OVERDUE));
        assertTrue(!reminderRepository.existsByRequiredReadingIdAndRecipientUserIdAndType(
                reading.getId(), completed.getId(), ReminderType.OVERDUE));

        var systemAudit = auditLogRepository.findAll().stream()
                .filter(row -> "SEND_AUTOMATIC_REMINDER".equals(row.getAction()))
                .filter(row -> row.getDetails() != null && row.getDetails().contains("OVERDUE"))
                .findFirst().orElseThrow();
        assertNull(systemAudit.getAdminId());
        assertEquals("სისტემა", systemAudit.getAdminNameSnapshot());
        JsonNode systemDetails = objectMapper.readTree(systemAudit.getDetails());
        assertEquals(1, systemDetails.path("schema_version").asInt());
        assertEquals("OVERDUE", systemDetails.path("after").path("delivery").asText());
        String rowHash = jdbcTemplate.queryForObject(
                "SELECT row_hash FROM audit_logs WHERE id = ?", String.class, systemAudit.getId());
        assertTrue(rowHash != null && !rowHash.isBlank());
    }

    @Test
    void primaryAndActingLeadersCanSendOnlyFixedScopedReminderWithCooldown() throws Exception {
        String department = uniqueDepartment("manual");
        Team ownTeam = team("own");
        Team otherTeam = team("other");
        User primary = user("reminder-primary", Role.MANAGER, department, null);
        User acting = user("reminder-acting", Role.MANAGER, department, null);
        User outsiderLeader = user("reminder-outsider", Role.MANAGER, department, null);
        User target = user("reminder-target", Role.OPERATOR, department, ownTeam.getId());
        assignment(primary, ownTeam, AssignmentType.PRIMARY);
        assignment(acting, ownTeam, AssignmentType.ACTING);
        assignment(outsiderLeader, otherTeam, AssignmentType.PRIMARY);
        reading(department, TbilisiTime.now().plusDays(1));

        mockMvc.perform(authed(post("/api/reminders/users/" + target.getId() + "/send"), tokenFor(outsiderLeader)))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(authed(post("/api/reminders/users/" + target.getId() + "/send"), tokenFor(primary))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ეს ტექსტი არ უნდა გაიგზავნოს\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MANUAL"))
                .andExpect(jsonPath("$.content").value(
                        "გთხოვთ გაეცნოთ თქვენთვის მინიჭებულ სავალდებულო მასალებს."))
                .andReturn().getResponse().getContentAsString();
        long reminderId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(authed(post("/api/reminders/users/" + target.getId() + "/send"), tokenFor(acting)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.retry_at").exists());

        var audit = auditLogRepository.findAll().stream()
                .filter(row -> "SEND_MANUAL_REMINDER".equals(row.getAction()) && row.getItemId().equals(reminderId))
                .findFirst().orElseThrow();
        assertEquals(primary.getId(), audit.getAdminId());
        assertEquals(target.getName(), audit.getItemNameSnapshot());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("SUCCESS", details.path("result").asText());
        assertEquals(1, details.path("after").path("pending_count").asInt());
        assertFalse(audit.getDetails().contains(target.getName()),
                "recipient name belongs in the protected target snapshot, not duplicated in details");
    }

    @Test
    void manualReminderRejectsEmployeeWithNothingPending() throws Exception {
        String department = uniqueDepartment("empty");
        Team team = team("empty");
        User leader = user("reminder-empty-leader", Role.MANAGER, department, null);
        User target = user("reminder-empty-target", Role.OPERATOR, department, team.getId());
        assignment(leader, team, AssignmentType.PRIMARY);

        mockMvc.perform(authed(post("/api/reminders/users/" + target.getId() + "/send"), tokenFor(leader)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("თანამშრომელს შეუსრულებელი სავალდებულო მასალა არ აქვს"));
    }

    private User user(String prefix, Role role, String department, Long teamId) {
        User user = new User();
        user.setEmail(prefix + "-" + System.nanoTime() + "@magti.ge");
        user.setName("ტესტ მომხმარებელი " + prefix);
        user.setRole(role);
        user.setDepartment(department);
        user.setTeamId(teamId);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream().map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private Team team(String prefix) {
        Team team = new Team();
        team.setName(prefix + "-" + System.nanoTime());
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private void assignment(User leader, Team team, AssignmentType type) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(leader.getId());
        assignment.setTeamId(team.getId());
        assignment.setAssignmentType(type);
        assignment.setActive(true);
        assignment.setStartedAt(TbilisiTime.now());
        leadershipRepository.saveAndFlush(assignment);
    }

    private RequiredReading reading(String department, java.time.OffsetDateTime dueAt) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(9_999_999L);
        reading.setTargetDepartment(department);
        reading.setDueDate(dueAt);
        reading.setPriority("normal");
        return readingRepository.saveAndFlush(reading);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String uniqueDepartment(String prefix) {
        return "Reminder-" + prefix + "-" + System.nanoTime();
    }
}
