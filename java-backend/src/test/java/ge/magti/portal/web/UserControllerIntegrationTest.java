package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- covers all
 * 13 built routers/users.py endpoints ({@code POST
 * /api/users/{user_id}/nudge} deferred, see {@link UserController}'s
 * javadoc).
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository permissionOverrideRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository articleTargetDepartmentRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditLog singleAudit(String action, Long itemId) {
        List<AuditLog> rows = auditRows(action, itemId);
        assertEquals(1, rows.size(), "expected exactly one audit row for " + action);
        return rows.getFirst();
    }

    private List<AuditLog> auditRows(String action, Long itemId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> action.equals(row.getAction()))
                .filter(row -> "user".equals(row.getItemType()))
                .filter(row -> itemId.equals(row.getItemId()))
                .toList();
    }

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი " + email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("CurrentPass1"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private void saveOverride(User user, Permission permission, UserPermissionOverride.State state) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(permission.value());
        override.setState(state);
        override.setUpdatedAt(TbilisiTime.now());
        permissionOverrideRepository.saveAndFlush(override);
    }

    private Article createArticle(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        // PO-40: an article operators cannot open binds nobody, so the fixture
        // is published for everyone rather than the entity's private draft.
        article.setStatus("published");
        article.setDraft(false);
        Article saved = articleRepository.saveAndFlush(article);
        ArticleTargetDepartment everyone = new ArticleTargetDepartment();
        everyone.setArticleId(saved.getId());
        everyone.setDepartment("All");
        articleTargetDepartmentRepository.saveAndFlush(everyone);
        return saved;
    }

    private RequiredReading createReading(Long articleId, String targetDepartment) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(articleId);
        reading.setTargetDepartment(targetDepartment);
        reading.setDueDate(TbilisiTime.now().plusDays(5));
        reading.setPriority("normal");
        return requiredReadingRepository.saveAndFlush(reading);
    }

    private void markReadDirect(User user, RequiredReading reading) {
        ReadStatus stat = new ReadStatus();
        stat.setUserId(user.getId());
        stat.setRequiredReadingId(reading.getId());
        stat.setStatus("read");
        stat.setReadAt(TbilisiTime.now());
        stat.setOperatorDepartmentSnapshot(user.getDepartment());
        readStatusRepository.saveAndFlush(stat);
    }

    // ── /api/me/effective-access ────────────────────────────────────────

    @Test
    void effectiveAccessRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me/effective-access"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void effectiveAccessIncludesOnlyTheCallersExplicitGrant() throws Exception {
        User operator = createUser("access-op@magti.ge", Role.OPERATOR, "All");
        User otherOperator = createUser("access-op-other@magti.ge", Role.OPERATOR, "All");
        saveOverride(operator, Permission.CONTENT_MANAGE, UserPermissionOverride.State.ALLOW);
        saveOverride(otherOperator, Permission.REPORTS_EXPORT, UserPermissionOverride.State.ALLOW);

        mockMvc.perform(authed(get("/api/me/effective-access"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("operator"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions[0]").value("content.manage"))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.bypass").value(false))
                .andExpect(jsonPath("$.can_publish_announcement").value(true));

        mockMvc.perform(authed(get("/api/me/effective-access"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("operator"))
                .andExpect(jsonPath("$.permissions[0]").value("reports.export"))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.bypass").value(false))
                .andExpect(jsonPath("$.can_publish_announcement").value(false));
    }

    @Test
    void effectiveAccessRemovesADeniedContentAdminDefaultButKeepsTheOthers() throws Exception {
        User contentAdmin = createUser("access-content-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        saveOverride(contentAdmin, Permission.CONTENT_MANAGE, UserPermissionOverride.State.DENY);

        String body = mockMvc.perform(authed(get("/api/me/effective-access"), tokenFor(contentAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("content_admin"))
                .andExpect(jsonPath("$.bypass").value(false))
                .andExpect(jsonPath("$.can_publish_announcement").value(false))
                .andReturn().getResponse().getContentAsString();

        List<String> permissions = StreamSupport.stream(
                        objectMapper.readTree(body).get("permissions").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertFalse(permissions.contains(Permission.CONTENT_MANAGE.value()));
        assertTrue(permissions.contains(Permission.ARTICLES_EDIT.value()));
        assertFalse(permissions.contains("system.audit"));
    }

    @Test
    void effectiveAccessMakesTheSystemAdminBypassAndFullCatalogExplicit() throws Exception {
        User admin = createUser("access-system-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        List<String> expected = Arrays.stream(Permission.values())
                .map(Permission::value)
                .sorted()
                .toList();

        String body = mockMvc.perform(authed(get("/api/me/effective-access"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.bypass").value(true))
                .andExpect(jsonPath("$.can_publish_announcement").value(true))
                .andReturn().getResponse().getContentAsString();

        List<String> permissions = StreamSupport.stream(
                        objectMapper.readTree(body).get("permissions").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertEquals(expected, permissions, "effective permissions must be stable and sorted on the wire");
    }

    // ── /api/users/me ───────────────────────────────────────────────────

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void getCurrentUserReportsAuditLogVisibilityByPermission() throws Exception {
        User operator = createUser("me-op1@magti.ge", Role.OPERATOR, "All");
        User admin = createUser("me-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(get("/api/users/me"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(operator.getEmail()))
                .andExpect(jsonPath("$.can_view_audit_log").value(false));

        mockMvc.perform(authed(get("/api/users/me"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(admin.getEmail()))
                .andExpect(jsonPath("$.can_view_audit_log").value(true));
    }

    @Test
    void updateCurrentUserChangesOnlyTheCallersProfileFields() throws Exception {
        User operator = createUser("me-op2@magti.ge", Role.OPERATOR, "All");
        User otherOperator = createUser("me-op2-other@magti.ge", Role.OPERATOR, "All");
        otherOperator.setName("სხვა მომხმარებელი");
        otherOperator.setPosition("სხვა პოზიცია");
        otherOperator.setPhone("599");
        otherOperator.setCardStyle("light");
        otherOperator = userRepository.saveAndFlush(otherOperator);

        // The name is the directory's (2026-10-01): sent unchanged, it is accepted.
        mockMvc.perform(authed(put("/api/users/me"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + operator.getName() + "\",\"phone\":\"555\",\"position\":\"ოპერატორი\",\"card_style\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(operator.getName()))
                .andExpect(jsonPath("$.phone").value("555"))
                .andExpect(jsonPath("$.card_style").value("dark"));

        User reloaded = userRepository.findById(operator.getId()).orElseThrow();
        assertEquals(operator.getName(), reloaded.getName());
        assertEquals(operator.getEmail(), reloaded.getEmail());
        assertEquals(Role.OPERATOR, reloaded.getRole());
        assertEquals("All", reloaded.getDepartment());

        User otherReloaded = userRepository.findById(otherOperator.getId()).orElseThrow();
        assertEquals("სხვა მომხმარებელი", otherReloaded.getName());
        assertEquals("სხვა პოზიცია", otherReloaded.getPosition());
        assertEquals("599", otherReloaded.getPhone());
        assertEquals("light", otherReloaded.getCardStyle());

        AuditLog audit = singleAudit("UPDATE_USER_PROFILE", operator.getId());
        assertEquals(operator.getId(), audit.getAdminId());
        assertEquals(operator.getName(), audit.getItemNameSnapshot());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals(1, details.path("schema_version").asInt());
        assertEquals("SUCCESS", details.path("result").asText());
        assertFalse(details.path("before").path("phone_present").asBoolean());
        assertTrue(details.path("after").path("phone_present").asBoolean());
        assertEquals("corporate", details.path("before").path("card_style").asText());
        assertEquals("dark", details.path("after").path("card_style").asText());
        assertFalse(audit.getDetails().contains("555"), "contact data must not be copied into audit details");
    }

    /**
     * Simulation, 2026-10-01: an operator renamed themselves "სისტემური
     * ადმინისტრატორი" for every screen and export. The directory owns names;
     * any change -- a blank one included -- is refused and nothing is saved.
     */
    @Test
    void selfUpdateRefusesANameChangeWithoutChangingProfileOrAudit() throws Exception {
        User operator = createUser("me-invalid-name@magti.ge", Role.OPERATOR, "All");
        String nameBefore = operator.getName();
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");

        for (String name : List.of(" ", "სისტემური ადმინისტრატორი")) {
            mockMvc.perform(authed(put("/api/users/me"), tokenFor(operator))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\",\"phone\":\"555\"}"))
                    .andExpect(status().isForbidden());
        }

        User reloaded = userRepository.findById(operator.getId()).orElseThrow();
        assertEquals(nameBefore, reloaded.getName());
        assertEquals(null, reloaded.getPhone());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    @Test
    void anonymousSelfUpdateCannotChangeAnyProfile() throws Exception {
        User operator = createUser("me-anonymous-boundary@magti.ge", Role.OPERATOR, "All");
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");
        String originalName = operator.getName();

        mockMvc.perform(put("/api/users/me").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"უცხო ცვლილება\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(originalName, userRepository.findById(operator.getId()).orElseThrow().getName());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    // ── self password change ────────────────────────────────────────────

    @Test
    void passwordChangesAreDirectoryOwnedAndRejectedWithoutMutationOrAudit() throws Exception {
        User operator = createUser("me-op3@magti.ge", Role.OPERATOR, "All");
        String hashBefore = operator.getHashedPassword();
        long auditsBefore = auditLogRepository.findAll().stream()
                .filter(row -> operator.getId().equals(row.getAdminId()) && !"ACCESS_DENIED".equals(row.getAction()))
                .count();

        mockMvc.perform(authed(post("/api/users/me/password"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"CurrentPass1\",\"new_password\":\"NewPass1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Active Directory")));
        mockMvc.perform(authed(post("/api/users/me/password"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        User reloaded = userRepository.findById(operator.getId()).orElseThrow();
        assertEquals(hashBefore, reloaded.getHashedPassword());
        long auditsAfter = auditLogRepository.findAll().stream()
                .filter(row -> operator.getId().equals(row.getAdminId()) && !"ACCESS_DENIED".equals(row.getAction()))
                .count();
        assertEquals(auditsBefore, auditsAfter, "a rejected directory-owned password change must not audit success");
    }

    // ── bulk role reassignment ──────────────────────────────────────────

    @Test
    void bulkReassignRequiresSystemAdmin() throws Exception {
        User manager = createUser("bulk-mgr1@magti.ge", Role.MANAGER, "All");
        User target = createUser("bulk-mgr-denied-target@magti.ge", Role.OPERATOR, "All");
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");

        mockMvc.perform(authed(post("/api/admin/roles/bulk-reassign"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + target.getId() + "],\"new_role\":\"manager\"}"))
                .andExpect(status().isForbidden());
        assertEquals(Role.OPERATOR, userRepository.findById(target.getId()).orElseThrow().getRole());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    @Test
    void bulkReassignRejectsUnknownRole() throws Exception {
        User admin = createUser("bulk-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("bulk-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/admin/roles/bulk-reassign"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + target.getId() + "],\"new_role\":\"superuser\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("უცნობი როლი"));
    }

    @Test
    void bulkReassignExcludesActingAdminAndReportsCounts() throws Exception {
        User admin = createUser("bulk-admin2@magti.ge", Role.SYSTEM_ADMIN, "All");
        User alreadyManager = createUser("bulk-mgr2@magti.ge", Role.MANAGER, "All");
        User operator = createUser("bulk-op2@magti.ge", Role.OPERATOR, "All");

        // Acting admin includes themselves in the request -- must be silently
        // dropped, never bulk-changed, and never blow up the "no users selected" guard.
        String body = "{\"user_ids\":[" + admin.getId() + "," + alreadyManager.getId() + "," + operator.getId()
                + "],\"new_role\":\"manager\"}";

        mockMvc.perform(authed(post("/api/admin/roles/bulk-reassign"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.new_role").value("manager"))
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.requested").value(3));

        User reloadedAdmin = userRepository.findById(admin.getId()).orElseThrow();
        User reloadedOperator = userRepository.findById(operator.getId()).orElseThrow();
        assertEquals(Role.SYSTEM_ADMIN, reloadedAdmin.getRole());
        assertEquals(Role.MANAGER, reloadedOperator.getRole());
        assertFalse(reloadedOperator.getPermissions().contains(Permission.REPORTS_EXPORT.value()),
                "role defaults are computed, not copied into legacy flat permissions");
        assertTrue(permissionOverrideRepository.findByUserId(operator.getId()).isEmpty());

        AuditLog audit = singleAudit("BULK_ROLE_operator_TO_manager", operator.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals(admin.getId(), audit.getAdminId());
        assertEquals(admin.getName(), audit.getAdminNameSnapshot());
        assertEquals(operator.getName(), audit.getItemNameSnapshot());
        assertEquals("operator", details.path("before").path("role").asText());
        assertEquals("manager", details.path("after").path("role").asText());
    }

    @Test
    void bulkReassignWithOnlySelfSelectedIsRejected() throws Exception {
        User admin = createUser("bulk-admin3@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(post("/api/admin/roles/bulk-reassign"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + admin.getId() + "],\"new_role\":\"manager\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "არცერთი მომხმარებელი არ არის შესარჩევი (საკუთარი როლის შეცვლა ჯგუფურად შეუძლებელია)."));
    }

    // ── bulk deactivation (PO-24) ───────────────────────────────────────

    @Test
    void bulkDeactivateRequiresSystemAdmin() throws Exception {
        User manager = createUser("bulk-off-mgr1@magti.ge", Role.MANAGER, "All");
        User operator = createUser("bulk-off-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/admin/users/bulk-deactivate"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + operator.getId() + "]}"))
                .andExpect(status().isForbidden());

        assertTrue(userRepository.findById(operator.getId()).orElseThrow().isActive(),
                "a refused sweep must not switch anybody off");
    }

    @Test
    void bulkDeactivateExcludesActingAdminSkipsTheAlreadyOffAndAudits() throws Exception {
        User admin = createUser("bulk-off-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User leaver = createUser("bulk-off-op2@magti.ge", Role.OPERATOR, "All");
        User alreadyOff = createUser("bulk-off-op3@magti.ge", Role.OPERATOR, "All");
        alreadyOff.setActive(false);
        userRepository.saveAndFlush(alreadyOff);

        // The acting admin is in the request, the way "select all" would put
        // them there. Dropped silently rather than refused, and never switched
        // off -- an administrator who locks themselves out of the only console
        // that can unlock them has no way back.
        String body = "{\"user_ids\":[" + admin.getId() + "," + leaver.getId() + "," + alreadyOff.getId() + "]}";

        mockMvc.perform(authed(post("/api/admin/users/bulk-deactivate"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivated").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.requested").value(3));

        assertTrue(userRepository.findById(admin.getId()).orElseThrow().isActive());
        assertFalse(userRepository.findById(leaver.getId()).orElseThrow().isActive());

        AuditLog audit = singleAudit("BULK_DEACTIVATE", leaver.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals(admin.getId(), audit.getAdminId());
        assertEquals(leaver.getName(), audit.getItemNameSnapshot());
        assertTrue(details.path("before").path("active").asBoolean());
        assertFalse(details.path("after").path("active").asBoolean());
        assertTrue(auditRows("BULK_DEACTIVATE", alreadyOff.getId()).isEmpty(),
                "an account that was already off must not be written, or audited, a second time");
    }

    @Test
    void bulkDeactivateCannotRemoveTheLastSystemAdmin() throws Exception {
        User admin = createUser("bulk-off-admin2@magti.ge", Role.SYSTEM_ADMIN, "All");
        User otherAdmin = createUser("bulk-off-admin3@magti.ge", Role.SYSTEM_ADMIN, "All");

        // Every system administrator the caller can reach, in one sweep. No
        // last-admin guard runs -- the caller is excluded from the set and is
        // an active system administrator by the time the handler runs, so one
        // always survives. This is that reasoning, asserted.
        List<Long> everyAdminId = userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.SYSTEM_ADMIN)
                .map(User::getId)
                .toList();
        String body = "{\"user_ids\":[" + everyAdminId.stream().map(String::valueOf)
                .collect(Collectors.joining(",")) + "]}";

        mockMvc.perform(authed(post("/api/admin/users/bulk-deactivate"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertTrue(userRepository.findById(admin.getId()).orElseThrow().isActive(),
                "the administrator running the sweep must still be able to sign in");
        assertFalse(userRepository.findById(otherAdmin.getId()).orElseThrow().isActive());
        assertTrue(userRepository.findAll().stream()
                        .anyMatch(user -> user.getRole() == Role.SYSTEM_ADMIN && user.isActive()),
                "at least one active system administrator must survive any sweep");
    }

    @Test
    void bulkDeactivateWithOnlySelfSelectedIsRejected() throws Exception {
        User admin = createUser("bulk-off-admin4@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(post("/api/admin/users/bulk-deactivate"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + admin.getId() + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "არცერთი მომხმარებელი არ არის შესარჩევი (საკუთარი ანგარიშის დეაქტივაცია ჯგუფურად შეუძლებელია)."));

        assertTrue(userRepository.findById(admin.getId()).orElseThrow().isActive());
    }

    // ── status ───────────────────────────────────────────────────────────

    @Test
    void updateUserStatusBlocksSelfDeactivationAndRequiresAdmin() throws Exception {
        User admin = createUser("status-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User operator = createUser("status-op1@magti.ge", Role.OPERATOR, "All");
        User manager = createUser("status-mgr1@magti.ge", Role.MANAGER, "All");

        mockMvc.perform(authed(put("/api/users/" + admin.getId() + "/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_active\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("საკუთარი ანგარიშის დეაქტივაცია არ შეიძლება"));

        long auditsBeforeDeniedStatus = auditLogRepository.countByActionNot("ACCESS_DENIED");
        mockMvc.perform(authed(put("/api/users/" + operator.getId() + "/status"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_active\":false}"))
                .andExpect(status().isForbidden());
        assertTrue(userRepository.findById(operator.getId()).orElseThrow().isActive());
        assertEquals(auditsBeforeDeniedStatus, auditLogRepository.countByActionNot("ACCESS_DENIED"));

        mockMvc.perform(authed(put("/api/users/" + operator.getId() + "/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_active").value(false));

        mockMvc.perform(authed(put("/api/users/999999999/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_active\":true}"))
                .andExpect(status().isNotFound());

        AuditLog audit = singleAudit("UPDATE_STATUS_TO_FALSE", operator.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertTrue(details.path("before").path("active").asBoolean());
        assertFalse(details.path("after").path("active").asBoolean());
    }

    // ── group leaders ───────────────────────────────────────────────────

    @Test
    void groupLeadersListsOnlyManagersAndRequiresAdmin() throws Exception {
        User admin = createUser("leaders-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User manager = createUser("leaders-mgr1@magti.ge", Role.MANAGER, "All");
        createUser("leaders-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(get("/api/admin/group-leaders"), tokenFor(manager)))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(authed(get("/api/admin/group-leaders"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = objectMapper.readTree(body);
        boolean managerPresent = StreamSupport.stream(arr.spliterator(), false)
                .anyMatch(n -> n.get("id").asLong() == manager.getId());
        boolean onlyManagers = StreamSupport.stream(arr.spliterator(), false)
                .allMatch(n -> n.has("name") && !n.has("role"));
        assertTrue(managerPresent);
        assertTrue(onlyManagers);
    }

    // ── list users (+ progress fields) ──────────────────────────────────

    @Test
    void listUsersRequiresAdminAndComputesProgressAndManagerFilter() throws Exception {
        User admin = createUser("list-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User manager = createUser("list-mgr1@magti.ge", Role.MANAGER, "All");
        User member = createUser("list-op1@magti.ge", Role.OPERATOR, "სია განყოფილება " + System.nanoTime());
        member.setManagerId(manager.getId());
        userRepository.saveAndFlush(member);
        User outsider = createUser("list-op2@magti.ge", Role.OPERATOR, "სხვა განყოფილება");

        Article article = createArticle("სავალდებულო სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), member.getDepartment());
        markReadDirect(member, reading);

        mockMvc.perform(authed(get("/api/users"), tokenFor(manager)))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(authed(get("/api/users"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = objectMapper.readTree(body);
        JsonNode memberNode = StreamSupport.stream(arr.spliterator(), false)
                .filter(n -> n.get("id").asLong() == member.getId())
                .findFirst().orElseThrow();
        assertEquals(1, memberNode.get("required_count").asInt());
        assertEquals(1, memberNode.get("read_count").asInt());
        assertEquals(100, memberNode.get("progress_percentage").asInt());

        String filteredBody = mockMvc.perform(authed(get("/api/users").param("manager_id", String.valueOf(manager.getId())), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode filtered = objectMapper.readTree(filteredBody);
        assertEquals(1, filtered.size());
        assertEquals(member.getId(), filtered.get(0).get("id").asLong());
        assertFalse(outsider.getId().equals(filtered.get(0).get("id").asLong()));
    }

    @Test
    void listUsersRejectsUnboundedRequestsAndPagesAtOracle() throws Exception {
        User admin = createUser("list-bounds-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User manager = createUser("list-bounds-manager@magti.ge", Role.MANAGER, "All");
        User first = createUser("list-bounds-first@magti.ge", Role.OPERATOR, "Paged directory");
        first.setManagerId(manager.getId());
        first = userRepository.saveAndFlush(first);
        User second = createUser("list-bounds-second@magti.ge", Role.OPERATOR, "Paged directory");
        second.setManagerId(manager.getId());
        second = userRepository.saveAndFlush(second);

        for (Map.Entry<String, String> invalid : Map.of(
                "skip", "-1",
                "limit", "1001").entrySet()) {
            mockMvc.perform(authed(get("/api/users").param(invalid.getKey(), invalid.getValue()), tokenFor(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(ListQueryBounds.INVALID_DETAIL));
        }

        String firstPageBody = mockMvc.perform(authed(get("/api/users")
                        .param("manager_id", String.valueOf(manager.getId()))
                        .param("skip", "0")
                        .param("limit", "1"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstPage = objectMapper.readTree(firstPageBody);
        assertEquals(1, firstPage.size());
        assertEquals(first.getId(), firstPage.get(0).get("id").asLong());

        String secondPageBody = mockMvc.perform(authed(get("/api/users")
                        .param("manager_id", String.valueOf(manager.getId()))
                        .param("skip", "1")
                        .param("limit", "1"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondPage = objectMapper.readTree(secondPageBody);
        assertEquals(1, secondPage.size());
        assertEquals(second.getId(), secondPage.get(0).get("id").asLong());
    }

    // ── admin update user ────────────────────────────────────────────────

    @Test
    void updateUserAdminChangesRoleDepartmentAndRejectsUnknownRole() throws Exception {
        User admin = createUser("upd-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("upd-op1@magti.ge", Role.OPERATOR, "ძველი განყოფილება");

        mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"nope\",\"department\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("უცნობი როლი"));

        mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"content_admin\",\"department\":\"ახალი განყოფილება\",\"position\":\"რედაქტორი\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("content_admin"))
                .andExpect(jsonPath("$.department").value("ახალი განყოფილება"));

        mockMvc.perform(authed(put("/api/users/999999999"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\"}"))
                .andExpect(status().isNotFound());

        AuditLog audit = singleAudit("UPDATE_USER_ADMIN", target.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("operator", details.path("before").path("role").asText());
        assertEquals("ძველი განყოფილება", details.path("before").path("department").asText());
        assertEquals("content_admin", details.path("after").path("role").asText());
        assertEquals("ახალი განყოფილება", details.path("after").path("department").asText());
    }

    @Test
    void nonAdminCannotChangeAnotherProfileOrPermissionOverrides() throws Exception {
        User manager = createUser("user-mutation-denied-manager@magti.ge", Role.MANAGER, "All");
        User target = createUser("user-mutation-denied-target@magti.ge", Role.OPERATOR, "საწყისი");
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");

        mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\",\"department\":\"უცხო\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"reports.export\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isForbidden());

        assertEquals("საწყისი", userRepository.findById(target.getId()).orElseThrow().getDepartment());
        assertTrue(permissionOverrideRepository.findByUserId(target.getId()).isEmpty());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    // ── teams ────────────────────────────────────────────────────────────

    @Test
    void teamsListIsAuthenticatedButInteractiveCreationIsDirectoryOwned() throws Exception {
        User operator = createUser("team-op1@magti.ge", Role.OPERATOR, "All");
        User admin = createUser("team-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        String teamName = "სატესტო გუნდი " + System.nanoTime();
        Team fixture = new Team();
        fixture.setName(teamName);
        fixture.setCreatedAt(TbilisiTime.now());
        teamRepository.saveAndFlush(fixture);

        mockMvc.perform(authed(post("/api/teams"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + teamName + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(post("/api/teams"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + teamName + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("ჯგუფების შექმნა იმართება ორგანიზაციის კატალოგიდან"));
        mockMvc.perform(authed(post("/api/teams"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(1, teamRepository.findAll().stream()
                .filter(team -> teamName.equals(team.getName())).count(),
                "a rejected interactive creation must not insert a second team");

        String listBody = mockMvc.perform(authed(get("/api/teams"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = objectMapper.readTree(listBody);
        assertTrue(StreamSupport.stream(arr.spliterator(), false).anyMatch(n -> n.get("name").asText().equals(teamName)));
    }

    // ── admin create user ────────────────────────────────────────────────

    @Test
    void lastActiveSystemAdminCannotBeDeactivated() throws Exception {
        User admin = createUser("last-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        jdbcTemplate.update("UPDATE users SET is_active = 0 WHERE role = 'admin' AND id <> ?", admin.getId());

        mockMvc.perform(authed(put("/api/users/" + admin.getId() + "/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserAdminIsDirectoryOwned() throws Exception {
        User admin = createUser("create-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        String email = "brand-new-user@magti.ge";
        long usersBefore = userRepository.count();
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");
        mockMvc.perform(authed(post("/api/users"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"name\":\"ახალი\",\"role\":\"content_admin\",\"password\":\"StrongPass1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Active Directory")));
        mockMvc.perform(authed(post("/api/users"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(usersBefore, userRepository.count());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    // ── admin reset password ────────────────────────────────────────────

    @Test
    void adminResetPasswordIsDirectoryOwned() throws Exception {
        User admin = createUser("reset-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("reset-op1@magti.ge", Role.OPERATOR, "All");
        String hashBefore = target.getHashedPassword();
        long auditsBefore = auditLogRepository.countByActionNot("ACCESS_DENIED");
        mockMvc.perform(authed(post("/api/users/" + target.getId() + "/reset-password"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"new_password\":\"StrongPass1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Active Directory")));
        mockMvc.perform(authed(post("/api/users/" + target.getId() + "/reset-password"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(hashBefore, userRepository.findById(target.getId()).orElseThrow().getHashedPassword());
        assertEquals(auditsBefore, auditLogRepository.countByActionNot("ACCESS_DENIED"));
    }

    // ── admin update permissions ─────────────────────────────────────────

    @Test
    void permissionDeltaValidatesCatalogPersistsBothStatesAndDeletesInherit() throws Exception {
        User admin = createUser("perm-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("perm-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"not.a.real.permission\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not.a.real.permission")));

        String allowed = mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"ALLOW\"},"
                                + "{\"permission\":\"reports.export\",\"state\":\"DENY\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission_overrides[*].state",
                        org.hamcrest.Matchers.containsInAnyOrder("ALLOW", "DENY")))
                .andReturn().getResponse().getContentAsString();
        long nextLock = objectMapper.readTree(allowed).get("lock_version").asLong();
        assertEquals(2, permissionOverrideRepository.findByUserId(target.getId()).size());

        AuditLog firstAudit = singleAudit("UPDATE_PERMISSIONS", target.getId());
        JsonNode firstDetails = objectMapper.readTree(firstAudit.getDetails());
        assertTrue(firstDetails.path("before").isObject());
        assertEquals(0, firstDetails.path("before").size());
        assertEquals("ALLOW", firstDetails.path("after").path("content.manage").asText());
        assertEquals("DENY", firstDetails.path("after").path("reports.export").asText());
        assertFalse(firstAudit.getDetails().contains(target.getEmail()),
                "target email must not be copied into permission details");

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":" + nextLock + ",\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"INHERIT\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission_overrides[*].permission",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("content.manage"))));
        assertEquals(List.of(Permission.REPORTS_EXPORT.value()), permissionOverrideRepository
                .findByUserId(target.getId()).stream().map(UserPermissionOverride::getPermission).toList());
        assertEquals(2, auditRows("UPDATE_PERMISSIONS", target.getId()).size());

        mockMvc.perform(authed(put("/api/users/999999999/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionDeltaRejectsAStaleLockVersion() throws Exception {
        User admin = createUser("perm-lock-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("perm-lock-target@magti.ge", Role.OPERATOR, "All");
        String body = "{\"lock_version\":0,\"overrides\":["
                + "{\"permission\":\"content.manage\",\"state\":\"ALLOW\"}]}";

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void permissionDeltaRequiresTheLockTokenAndValidatesNestedFields() throws Exception {
        User admin = createUser("perm-validation-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("perm-validation-target@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":null}]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, userRepository.findById(target.getId()).orElseThrow().getLockVersion());
        assertTrue(permissionOverrideRepository.findByUserId(target.getId()).isEmpty());
    }

    @Test
    void profileRoleAndPermissionDeltasCommitAtomicallyAgainstTheOriginalLock() throws Exception {
        User admin = createUser("atomic-profile-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("atomic-profile-target@magti.ge", Role.OPERATOR, "All");
        long lockBefore = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM users WHERE id = ?", Long.class, target.getId());

        String updated = mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"manager\",\"department\":\"Technical\","
                                + "\"position\":\"Lead\",\"lock_version\":" + lockBefore + ",\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("manager"))
                .andExpect(jsonPath("$.permission_overrides[0].permission").value("content.manage"))
                .andExpect(jsonPath("$.permission_overrides[0].state").value("ALLOW"))
                .andReturn().getResponse().getContentAsString();

        long responseLock = objectMapper.readTree(updated).get("lock_version").asLong();
        long lockAfter = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM users WHERE id = ?", Long.class, target.getId());
        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertEquals(Role.MANAGER, reloaded.getRole());
        assertEquals("Lead", reloaded.getPosition());
        assertEquals(responseLock, reloaded.getLockVersion(), "response must carry the committed JPA version");
        assertEquals(lockBefore + 1, lockAfter, "one successful atomic PUT must advance lock_version exactly once");
        assertEquals(lockAfter, responseLock, "response must carry the exact version stored by Oracle");

        mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\",\"department\":\"Office\","
                                + "\"position\":\"Stale\",\"lock_version\":" + lockBefore + ",\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"DENY\"}]}"))
                .andExpect(status().isConflict());

        User afterConflict = userRepository.findById(target.getId()).orElseThrow();
        assertEquals(Role.MANAGER, afterConflict.getRole());
        assertEquals("Lead", afterConflict.getPosition());
        assertEquals(UserPermissionOverride.State.ALLOW, permissionOverrideRepository
                .findByUserId(target.getId()).getFirst().getState());

        AuditLog profileAudit = singleAudit("UPDATE_USER_ADMIN", target.getId());
        JsonNode profileDetails = objectMapper.readTree(profileAudit.getDetails());
        assertEquals("operator", profileDetails.path("before").path("role").asText());
        assertEquals("manager", profileDetails.path("after").path("role").asText());
        AuditLog permissionAudit = singleAudit("UPDATE_PERMISSIONS", target.getId());
        JsonNode permissionDetails = objectMapper.readTree(permissionAudit.getDetails());
        assertEquals("ALLOW", permissionDetails.path("after").path("content.manage").asText());
    }

    @Test
    void permissionOnlyDeltaAdvancesTheOriginalLockExactlyOnce() throws Exception {
        User admin = createUser("atomic-cas-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("atomic-cas-target@magti.ge", Role.OPERATOR, "All");
        target.setPosition("Operator");
        target = userRepository.saveAndFlush(target);
        long lockBefore = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM users WHERE id = ?", Long.class, target.getId());

        String updated = mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\",\"department\":\"All\","
                                + "\"position\":\"Operator\",\"lock_version\":" + lockBefore + ",\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("operator"))
                .andExpect(jsonPath("$.department").value("All"))
                .andExpect(jsonPath("$.position").value("Operator"))
                .andExpect(jsonPath("$.permission_overrides[0].permission").value("content.manage"))
                .andExpect(jsonPath("$.permission_overrides[0].state").value("ALLOW"))
                .andReturn().getResponse().getContentAsString();

        long responseLock = objectMapper.readTree(updated).get("lock_version").asLong();
        long lockAfter = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM users WHERE id = ?", Long.class, target.getId());
        List<UserPermissionOverride> overrides = permissionOverrideRepository.findByUserId(target.getId());

        assertEquals(lockBefore + 1, lockAfter,
                "the permission-only CAS path must advance lock_version exactly once");
        assertEquals(lockAfter, responseLock, "response must carry the exact version stored by Oracle");
        assertEquals(1, overrides.size());
        assertEquals(Permission.CONTENT_MANAGE.value(), overrides.getFirst().getPermission());
        assertEquals(UserPermissionOverride.State.ALLOW, overrides.getFirst().getState());
    }

    @Test
    void bulkRoleChangePreservesExplicitOverridesAndDoesNotMaterializeDefaults() throws Exception {
        User admin = createUser("perm-role-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("perm-role-target@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"content.manage\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/admin/roles/bulk-reassign"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + target.getId() + "],\"new_role\":\"manager\"}"))
                .andExpect(status().isOk());

        List<UserPermissionOverride> rows = permissionOverrideRepository.findByUserId(target.getId());
        assertEquals(1, rows.size());
        assertEquals(Permission.CONTENT_MANAGE.value(), rows.getFirst().getPermission());
        assertEquals(UserPermissionOverride.State.ALLOW, rows.getFirst().getState());
        assertFalse(rows.stream().anyMatch(row -> row.getPermission().equals(Permission.REPORTS_EXPORT.value())),
                "the manager role default must not be copied into the override table");
    }

    /**
     * SEC-06, the part the audit did not surface. {@code users.manage} was
     * not merely unenforced -- it was unenforceable. PermissionChecker
     * returns true unconditionally for SYSTEM_ADMIN, and every
     * user-administration endpoint also requires that role, so the
     * permission was only ever evaluated for the one role that skips the
     * evaluation. This test is what found it: an admin stripped of the
     * permission still sailed through, because the check could not fail.
     *
     * <p>It is now removed from the catalog rather than enforced, so the
     * assertion is that the switch is gone -- not that it works.
     */
    @Test
    void aSystemAdminBypassesEveryPermissionSoNoAdminOnlyPermissionCanBind() throws Exception {
        User admin = createUser("sec06-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        admin.setPermissions(new LinkedHashSet<>());
        userRepository.saveAndFlush(admin);

        // No permissions at all, and still allowed -- by design (root role),
        // but it is why an admin-only permission is decorative.
        mockMvc.perform(authed(get("/api/users"), tokenFor(admin)))
                .andExpect(status().isOk());
    }

    /** articles.view was removed from the catalog by SEC-06, so it must now be rejected as unknown. */
    @Test
    void articlesViewIsNoLongerAnAcceptedPermission() throws Exception {
        User admin = createUser("sec06-av@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("sec06-av-target@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":0,\"overrides\":["
                                + "{\"permission\":\"articles.view\",\"state\":\"ALLOW\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("articles.view")));
    }

    /**
     * SEC-12: updateUserAdmin had NEITHER guard its siblings apply.
     * bulkReassignRoles refuses to leave zero active system admins and drops
     * the caller from its own target list; updateUserStatus refuses
     * self-deactivation. Here an admin could demote the last SYSTEM_ADMIN --
     * including themselves -- and lock the organisation out of every
     * administrative screen with no way back through the product.
     */
    @Test
    void theLastSystemAdminCannotBeDemotedThroughTheSingleUserEndpoint() throws Exception {
        User admin = createUser("sec12-admin@magti.ge", Role.SYSTEM_ADMIN, "All");

        // Any OTHER admin still present makes the demotion safe, so the test
        // has to establish that this really is the last one.
        long otherActiveAdmins = userRepository.countByRoleAndActiveTrueAndIdNotIn(
                Role.SYSTEM_ADMIN, List.of(admin.getId()));
        org.junit.jupiter.api.Assumptions.assumeTrue(otherActiveAdmins == 0,
                "shared dev Oracle already has other active admins; the last-admin path is not reachable here");

        mockMvc.perform(authed(put("/api/users/" + admin.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\",\"department\":\"All\",\"position\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(Role.SYSTEM_ADMIN, userRepository.findById(admin.getId()).orElseThrow().getRole());
    }

    /** The self-targeting half of SEC-12, which holds regardless of how many admins exist. */
    @Test
    void anAdminCannotDemoteThemselvesThroughTheSingleUserEndpoint() throws Exception {
        User admin = createUser("sec12-self@magti.ge", Role.SYSTEM_ADMIN, "All");
        createUser("sec12-spare@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(put("/api/users/" + admin.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"content_admin\",\"department\":\"All\",\"position\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(Role.SYSTEM_ADMIN, userRepository.findById(admin.getId()).orElseThrow().getRole());
    }

    /**
     * SEC-12's quieter half: department and position were assigned with no
     * null check, unlike phone/teamId below them, so an update omitting
     * either silently blanked it. department drives every visibility and
     * compliance query the user appears in.
     */
    @Test
    void omittingDepartmentDoesNotBlankIt() throws Exception {
        User admin = createUser("sec12-null-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User target = createUser("sec12-null-target@magti.ge", Role.OPERATOR, "ტექნიკური");

        mockMvc.perform(authed(put("/api/users/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\"}"))
                .andExpect(status().isOk());

        assertEquals("ტექნიკური", userRepository.findById(target.getId()).orElseThrow().getDepartment(),
                "a payload that does not mention department must not erase it");
    }
}
