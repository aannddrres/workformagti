package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.AuditCategory;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.SearchLog;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- covers all 12
 * routers/stats.py endpoints, reusing {@link ge.magti.portal.compliance.ComplianceQueryService}
 * and the Stats builders (already unit-tested on their own) so this file
 * focuses on the DB-query wiring and RBAC gates rather than re-proving the
 * pure aggregation logic.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository permissionOverrideRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private SearchLogRepository searchLogRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LeadershipAssignmentRepository leadershipAssignmentRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private User createStatsViewer(String email, String department) {
        User user = createUser(email, Role.OPERATOR, department);
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(Permission.STATS_VIEW.value());
        override.setState(UserPermissionOverride.State.ALLOW);
        override.setUpdatedAt(TbilisiTime.now());
        override.setUpdatedBy(user.getId());
        permissionOverrideRepository.saveAndFlush(override);
        return user;
    }

    private void assertAggregateStatsStatus(User caller, int expectedStatus) throws Exception {
        String token = tokenFor(caller);
        mockMvc.perform(authed(get("/api/statistics/activity"), token))
                .andExpect(status().is(expectedStatus));
        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "role"), token))
                .andExpect(status().is(expectedStatus));
        mockMvc.perform(authed(get("/api/statistics/compliance"), token))
                .andExpect(status().is(expectedStatus));
        mockMvc.perform(authed(get("/api/statistics/failed-searches"), token))
                .andExpect(status().is(expectedStatus));
        mockMvc.perform(authed(get("/api/statistics/kpi"), token))
                .andExpect(status().is(expectedStatus));
        mockMvc.perform(authed(get("/api/statistics/popular-searches"), token))
                .andExpect(status().is(expectedStatus));
    }

    /** {@link #createUser} with a caller-chosen display name -- needed wherever a test asserts on names. */
    private User namedUser(String email, Role role, String department, String name) {
        User user = createUser(email, role, department);
        user.setName(name);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Article createArticle(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        return articleRepository.saveAndFlush(article);
    }

    private Team createTeam(String name) {
        Team team = new Team();
        team.setName(name);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private Team createTeam(String name, Long departmentId) {
        Team team = createTeam(name);
        team.setDepartmentId(departmentId);
        return teamRepository.saveAndFlush(team);
    }

    private void assignTeam(User leader, Team team, AssignmentType type) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(leader.getId());
        assignment.setTeamId(team.getId());
        assignment.setAssignmentType(type);
        assignment.setActive(true);
        assignment.setStartedAt(TbilisiTime.now());
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        leadershipAssignmentRepository.saveAndFlush(assignment);
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

    private void createSearchLog(Long userId, String term, boolean hasResults) {
        SearchLog log = new SearchLog();
        log.setUserId(userId);
        log.setSearchTerm(term);
        log.setHasResults(hasResults);
        log.setTimestamp(TbilisiTime.now());
        searchLogRepository.saveAndFlush(log);
    }

    private void assertSearchTermCount(String responseBody, String term, long expectedCount) throws Exception {
        boolean found = false;
        for (var row : objectMapper.readTree(responseBody)) {
            if (term.equals(row.path("search_term").asText())
                    && expectedCount == row.path("count").asLong()) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected search term/count was absent from the bounded response: "
                + term + "/" + expectedCount);
    }

    private void createAuditLog(Long adminId, OffsetDateTime timestamp, AuditCategory category) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction("TEST_ACTION");
        log.setItemType("article");
        log.setItemId(1L);
        log.setTimestamp(timestamp);
        log.setCategory(category);
        auditLogRepository.saveAndFlush(log);
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/statistics/kpi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorIsForbiddenFromAdminStatsEndpoints() throws Exception {
        User operator = createUser("stats-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(get("/api/statistics/kpi"), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/manager/department-stats"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void statsViewIsIndependentFromContentManageAcrossAllSixAggregates() throws Exception {
        User contentManager = createUser("stats-content-only@magti.ge", Role.CONTENT_ADMIN, "All");
        User statsViewer = createStatsViewer("stats-view-only@magti.ge", "All");
        User systemAdmin = createUser("stats-bypass-admin@magti.ge", Role.SYSTEM_ADMIN, "All");

        assertAggregateStatsStatus(contentManager, 403);
        assertAggregateStatsStatus(statsViewer, 200);
        assertAggregateStatsStatus(systemAdmin, 200);

        mockMvc.perform(authed(get("/api/admin/articles/stale"), tokenFor(statsViewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void kpiCountsReflectSeededData() throws Exception {
        User admin = createStatsViewer("stats-kpi-admin@magti.ge", "All");
        long articlesBefore = articleRepository.count();
        createArticle("KPI სტატია 1");
        createArticle("KPI სტატია 2");

        mockMvc.perform(authed(get("/api/statistics/kpi"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").value(articlesBefore + 2))
                .andExpect(jsonPath("$.users").isNumber())
                .andExpect(jsonPath("$.required_readings").isNumber())
                .andExpect(jsonPath("$.videos").isNumber());
    }

    @Test
    void popularAndFailedSearchesGroupNormalizedTermsCaseAndWhitespace() throws Exception {
        // Both endpoints aggregate across ALL search_logs and return only the top
        // 10. Extend the current leading bucket instead of assuming a new count=2
        // term will outrank persistent test/seed data on a reused Oracle schema.
        User admin = createStatsViewer("stats-search-admin@magti.ge", "All");
        User op = createUser("stats-search-op@magti.ge", Role.OPERATOR, "All");
        var currentPopular = searchLogRepository.popularSearchTerms(PageRequest.of(0, 1));
        String popularTerm = currentPopular.isEmpty()
                ? "unique-popular-" + System.nanoTime()
                : (String) currentPopular.getFirst()[0];
        long popularCountBefore = currentPopular.isEmpty()
                ? 0L
                : ((Number) currentPopular.getFirst()[1]).longValue();
        String popularVariant = popularTerm.length() <= 496
                ? "  " + popularTerm.toUpperCase(java.util.Locale.ROOT) + "  "
                : popularTerm.toUpperCase(java.util.Locale.ROOT);
        createSearchLog(op.getId(), popularVariant, true);
        createSearchLog(op.getId(), popularVariant, true);

        var currentFailed = searchLogRepository.failedSearchTerms(PageRequest.of(0, 1));
        String failedTerm = currentFailed.isEmpty()
                ? "unique-failed-" + System.nanoTime()
                : (String) currentFailed.getFirst()[0];
        long failedCountBefore = currentFailed.isEmpty()
                ? 0L
                : ((Number) currentFailed.getFirst()[1]).longValue();
        String failedVariant = failedTerm.length() <= 496
                ? "  " + failedTerm.toUpperCase(java.util.Locale.ROOT) + "  "
                : failedTerm.toUpperCase(java.util.Locale.ROOT);
        createSearchLog(op.getId(), failedVariant, false);

        String popularBody = mockMvc.perform(authed(
                        get("/api/statistics/popular-searches"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertSearchTermCount(popularBody, popularTerm, popularCountBefore + 2);

        String failedBody = mockMvc.perform(authed(
                        get("/api/statistics/failed-searches"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertSearchTermCount(failedBody, failedTerm, failedCountBefore + 1);
    }

    @Test
    void complianceStatisticsReturnsWellFormedPercentagesAndArticleList() throws Exception {
        // compute_compliance() is org-wide (routers/stats.py:161), so this asserts
        // shape/bounds rather than an exact figure -- real seeded data on this
        // Oracle instance already contributes to the numerator/denominator.
        User admin = createStatsViewer("stats-comp-admin@magti.ge", "All");
        User op1 = createUser("stats-comp-op1@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle("კომპლაენს სტატია");
        RequiredReading reading = createReading(article.getId(), "All");
        markReadDirect(op1, reading);

        String body = mockMvc.perform(authed(get("/api/statistics/compliance"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.top_articles").isArray())
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(body);
        double readPct = node.get("read_percentage").asDouble();
        double unreadPct = node.get("unread_percentage").asDouble();
        org.junit.jupiter.api.Assertions.assertTrue(readPct >= 0.0 && readPct <= 100.0);
        org.junit.jupiter.api.Assertions.assertTrue(unreadPct >= 0.0 && unreadPct <= 100.0);
        org.junit.jupiter.api.Assertions.assertEquals(100.0, readPct + unreadPct, 0.01);
    }

    @Test
    void userProgressIsSystemAdminOnly() throws Exception {
        User contentAdmin = createUser("stats-up-ca@magti.ge", Role.CONTENT_ADMIN, "All");
        User sysAdmin = createUser("stats-up-sa@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(get("/api/statistics/user-progress"), tokenFor(contentAdmin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/statistics/user-progress"), tokenFor(sysAdmin)))
                .andExpect(status().isOk());
    }

    @Test
    void userProgressRejectsUnboundedRequestsAndPagesActiveOperatorsAtOracle() throws Exception {
        User sysAdmin = createUser("stats-up-bounds-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        createUser("stats-up-bounds-op1@magti.ge", Role.OPERATOR, "All");
        createUser("stats-up-bounds-op2@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(get("/api/statistics/user-progress").param("skip", "-1"), tokenFor(sysAdmin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ListQueryBounds.INVALID_DETAIL));
        mockMvc.perform(authed(get("/api/statistics/user-progress").param("limit", "1001"), tokenFor(sysAdmin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ListQueryBounds.INVALID_DETAIL));

        String firstBody = mockMvc.perform(authed(get("/api/statistics/user-progress")
                        .param("skip", "0").param("limit", "1"), tokenFor(sysAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String secondBody = mockMvc.perform(authed(get("/api/statistics/user-progress")
                        .param("skip", "1").param("limit", "1"), tokenFor(sysAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        long firstId = objectMapper.readTree(firstBody).get(0).get("user_id").asLong();
        long secondId = objectMapper.readTree(secondBody).get(0).get("user_id").asLong();
        assertTrue(firstId < secondId, "successive Oracle slices must use stable user-id ordering");
    }

    @Test
    void adminTeamStatsScopesByTeamIdAndComputesAverage() throws Exception {
        User admin = createUser("stats-team-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User contentAdmin = createUser("stats-team-content@magti.ge", Role.CONTENT_ADMIN, "All");
        Team team = createTeam("სტატისტიკის გუნდი " + System.nanoTime());
        User member = createUser("stats-team-member@magti.ge", Role.OPERATOR, "All");
        member.setTeamId(team.getId());
        userRepository.saveAndFlush(member);
        Article article = createArticle("გუნდის სტატია");
        RequiredReading reading = createReading(article.getId(), "All");
        markReadDirect(member, reading);

        mockMvc.perform(authed(get("/api/admin/stats/team/" + team.getId()), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team_id").value(team.getId().intValue()))
                .andExpect(jsonPath("$.average_percentage").value("100%"))
                .andExpect(jsonPath("$.members[0].user_id").value(member.getId().intValue()));
        mockMvc.perform(authed(get("/api/admin/stats/team/" + team.getId()), tokenFor(contentAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerTeamStatsIsPinnedToOwnDepartmentEvenIfQueryParamSaysOtherwise() throws Exception {
        User manager = createUser("stats-mgr@magti.ge", Role.MANAGER, "ოფისი");
        User officeOp = createUser("stats-mgr-office-op@magti.ge", Role.OPERATOR, "ოფისი");
        User techOp = createUser("stats-mgr-tech-op@magti.ge", Role.OPERATOR, "ტექნიკური");

        String body = mockMvc.perform(authed(get("/api/manager/team-stats").param("department", "ტექნიკური"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("ოფისი"))
                .andReturn().getResponse().getContentAsString();

        var membersNode = objectMapper.readTree(body).get("members");
        boolean containsTechOp = false;
        boolean containsOfficeOp = false;
        for (var m : membersNode) {
            long id = m.get("user_id").asLong();
            if (id == techOp.getId()) containsTechOp = true;
            if (id == officeOp.getId()) containsOfficeOp = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(containsOfficeOp);
        org.junit.jupiter.api.Assertions.assertFalse(containsTechOp, "a manager must never see another department's members");
    }

    @Test
    void managerTeamStatsDefaultsToAssignedTeamAndRejectsForeignTeamId() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String department = "ერთიდეპარტამენტი-" + suffix;
        Team ownTeam = createTeam("team-stats-own-" + suffix);
        Team foreignTeam = createTeam("team-stats-foreign-" + suffix);

        User manager = namedUser("stats-team-scope-manager@magti.ge", Role.MANAGER, department,
                "Team scope manager " + suffix);
        manager.setTeamId(ownTeam.getId());
        manager = userRepository.saveAndFlush(manager);

        User ownMember = namedUser("stats-team-scope-own@magti.ge", Role.OPERATOR, department,
                "Own team member " + suffix);
        ownMember.setTeamId(ownTeam.getId());
        ownMember = userRepository.saveAndFlush(ownMember);

        User foreignMember = namedUser("stats-team-scope-foreign@magti.ge", Role.OPERATOR, department,
                "Foreign team member " + suffix);
        foreignMember.setTeamId(foreignTeam.getId());
        foreignMember = userRepository.saveAndFlush(foreignMember);

        String defaultBody = mockMvc.perform(authed(get("/api/manager/team-stats"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(defaultBody.contains(ownMember.getName()),
                "the manager's primary team remains the default interactive scope");
        assertFalse(defaultBody.contains(foreignMember.getName()),
                "a sibling team in the same department must not leak through the default response");

        mockMvc.perform(authed(get("/api/manager/team-stats")
                        .param("team_id", foreignTeam.getId().toString()), tokenFor(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("არჩეული ჯგუფი თქვენს აქტიურ დანიშვნებში არ შედის"));

        mockMvc.perform(authed(get("/api/manager/team-stats")
                        .param("team_id", ownTeam.getId().toString()), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.user_id == " + ownMember.getId() + ")]").exists());
    }

    @Test
    void adminTeamStatsDefaultsToAllDepartmentsWithoutParam() throws Exception {
        User admin = createUser("stats-team-all-admin@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(get("/api/manager/team-stats"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("All"));
    }

    @Test
    void departmentStatsBuildsExecutiveDashboardTree() throws Exception {
        User manager = createUser("stats-dept-mgr@magti.ge", Role.MANAGER, "ტექნიკური — ჯგუფი 01");
        User op = createUser("stats-dept-op@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Article article = createArticle("დეპარტამენტის სტატია");
        RequiredReading reading = createReading(article.getId(), "All");
        markReadDirect(op, reading);

        mockMvc.perform(authed(get("/api/manager/department-stats"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.total_members").isNumber())
                .andExpect(jsonPath("$.departments[?(@.name == 'ტექნიკური')].is_empty").value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.generated_at").exists());
    }

    /** Phase-0 guard: sibling rows are absent, while the manager's own group remains useful. */
    @Test
    void departmentStatsContainsOnlyManagerScopeButAdminRemainsOrgWide() throws Exception {
        User manager = createUser("stats-sec03-mgr@magti.ge", Role.MANAGER, "ტექნიკური — ჯგუფი 01");
        User admin = createUser("stats-sec03-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        // createUser gives everyone the same name, which would make the
        // "is this operator named in the body" assertions below vacuous.
        User ownOp = namedUser("stats-sec03-own@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01",
                "სეკ03 თავისი " + System.nanoTime());
        User otherOp = namedUser("stats-sec03-other@magti.ge", Role.OPERATOR, "ოფისი — ჯგუფი 01",
                "სეკ03 სხვისი " + System.nanoTime());
        Article article = createArticle("SEC-03 სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), "All");
        markReadDirect(ownOp, reading);
        markReadDirect(otherOp, reading);

        String managerBody = mockMvc.perform(authed(get("/api/manager/department-stats"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.total_members")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.departments[?(@.name == 'ტექნიკური')].member_count")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThan(0))))
                .andReturn().getResponse().getContentAsString();
        assertFalse(managerBody.contains(otherOp.getName()),
                "SEC-03: another department's operator must not be named in a manager's dashboard");
        assertTrue(managerBody.contains(ownOp.getName()),
                "the manager's own scoped group must remain usable");
        var managerDepartments = objectMapper.readTree(managerBody).get("departments");
        assertEquals(1, managerDepartments.size(), "sibling department rows must be absent, not redacted");
        assertEquals("ტექნიკური", managerDepartments.get(0).get("name").asText());
        assertEquals(1, managerDepartments.get(0).get("groups").size(), "sibling group rows must be absent");
        assertEquals("ჯგუფი 01", managerDepartments.get(0).get("groups").get(0).get("name").asText());

        // system_admin is unchanged: still the full named tree.
        String adminBody = mockMvc.perform(authed(get("/api/manager/department-stats"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(adminBody.contains(ownOp.getName()) && adminBody.contains(otherOp.getName()),
                "system_admin must keep the full per-person dashboard");
    }

    @Test
    void criticalOperatorsListsUsersBelowThreshold() throws Exception {
        User admin = createUser("stats-crit-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User contentAdmin = createUser("stats-crit-content@magti.ge", Role.CONTENT_ADMIN, "All");
        User laggingOp = createUser("stats-crit-op@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle("კრიტიკული სტატია");
        createReading(article.getId(), "All"); // not read by anyone -> 0%

        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[?(@.user_id == " + laggingOp.getId() + ")]").exists())
                .andExpect(jsonPath("$.generated_at").exists());
        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(contentAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupUsersDrillDownMatchesDepartmentBucketAndGroupLabel() throws Exception {
        // Uses a made-up, unlikely-to-collide group suffix rather than asserting
        // an exact list size, since get_group_users scopes by department bucket
        // + exact group label across ALL active users on this Oracle instance.
        User admin = createUser("stats-grp-admin@magti.ge", Role.SYSTEM_ADMIN, "All");
        User contentAdmin = createUser("stats-grp-content@magti.ge", Role.CONTENT_ADMIN, "All");
        User groupMember = createUser("stats-grp-member@magti.ge", Role.OPERATOR, "საინფორმაციო — ჯგუფი ტესტ99");
        User outsider = createUser("stats-grp-outsider@magti.ge", Role.OPERATOR, "საინფორმაციო — ჯგუფი ტესტ98");

        String body = mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "საინფორმაციო", "ჯგუფი ტესტ99"),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("საინფორმაციო"))
                .andExpect(jsonPath("$.group_name").value("ჯგუფი ტესტ99"))
                .andReturn().getResponse().getContentAsString();

        var usersNode = objectMapper.readTree(body).get("users");
        boolean containsMember = false;
        boolean containsOutsider = false;
        for (var u : usersNode) {
            long id = u.get("user_id").asLong();
            if (id == groupMember.getId()) containsMember = true;
            if (id == outsider.getId()) containsOutsider = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(containsMember);
        org.junit.jupiter.api.Assertions.assertFalse(containsOutsider, "a different group suffix must not match");
        mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "საინფორმაციო", "ჯგუფი ტესტ99"),
                        tokenFor(contentAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanDrillIntoCriticalOperatorsAndGroupUsersFromTheirOwnDashboard() throws Exception {
        // Regression for the manager-access fix in StatsController: both drill-downs
        // used to 403 a plain manager even though the dashboard they're launched
        // from (department-stats) has always allowed managers. Operator alone must
        // still be denied -- this isn't a blanket permitAll.
        User manager = createUser("stats-mgr-drilldown@magti.ge", Role.MANAGER, "საინფორმაციო — ჯგუფი ტესტ99");
        User operator = createUser("stats-op-drilldown@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(manager)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "საინფორმაციო", "ჯგუფი ტესტ99"),
                        tokenFor(manager)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCriticalOperatorsAndGroupUsersAreScopedToTheirOwnDepartmentOnly() throws Exception {
        // Regression for bug #312: both drill-downs used to be completely unscoped
        // for managers -- confirmed live, a manager in one group received another
        // group's (even another department's) named operator data. SYSTEM_ADMIN
        // keeps the unscoped org-wide view; managers get hard-pinned and
        // content-admin-only callers are denied.
        User manager = createUser("stats-mgr-scope@magti.ge", Role.MANAGER, "ტექნიკური — ჯგუფი 03");
        User ownOp = createUser("stats-mgr-scope-own-op@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
        User otherGroupOp = createUser("stats-mgr-scope-other-op@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Article article = createArticle("სქოუპის სტატია");
        createReading(article.getId(), "All"); // unread by anyone -> both operators are 0%, i.e. critical

        String body = mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var operatorsNode = objectMapper.readTree(body).get("operators");
        boolean containsOwnOp = false;
        boolean containsOtherGroupOp = false;
        for (var o : operatorsNode) {
            long id = o.get("user_id").asLong();
            if (id == ownOp.getId()) containsOwnOp = true;
            if (id == otherGroupOp.getId()) containsOtherGroupOp = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(containsOwnOp);
        org.junit.jupiter.api.Assertions.assertFalse(containsOtherGroupOp,
                "a manager must never see another group's critical operators");

        // Own group/department -> allowed.
        mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "ტექნიკური", "ჯგუფი 03"),
                        tokenFor(manager)))
                .andExpect(status().isOk());

        // Same department bucket, different group -> now rejected (used to succeed).
        mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "ტექნიკური", "ჯგუფი 01"),
                        tokenFor(manager)))
                .andExpect(status().isForbidden());

        // Different department bucket entirely -> also rejected.
        mockMvc.perform(authed(
                        get("/api/admin/departments/{department}/groups/{groupName}/users",
                                "ოფისი", "ჯგუფი 01"),
                        tokenFor(manager)))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignmentBackedLegacyGroupPathAuthorizesCanonicalTargetEvenWhenItIsEmpty() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        Department department = departmentRepository.findByName("ტექნიკური").orElseThrow();
        String ownGroupName = "ჯგუფი path-own-" + suffix;
        String emptyAssignedGroupName = "ჯგუფი path-empty-" + suffix;
        String foreignGroupName = "ჯგუფი path-foreign-" + suffix;
        Team ownTeam = createTeam(ownGroupName, department.getId());
        Team emptyAssignedTeam = createTeam(emptyAssignedGroupName, department.getId());
        Team foreignTeam = createTeam(foreignGroupName, department.getId());

        User manager = namedUser("stats-group-path-manager-" + suffix + "@magti.ge", Role.MANAGER,
                "ტექნიკური — " + ownGroupName, "Path manager " + suffix);
        manager.setTeamId(ownTeam.getId());
        manager = userRepository.saveAndFlush(manager);
        assignTeam(manager, emptyAssignedTeam, AssignmentType.ACTING);

        User ownMember = namedUser("stats-group-path-own-" + suffix + "@magti.ge", Role.OPERATOR,
                "ტექნიკური — " + ownGroupName, "Path own " + suffix);
        ownMember.setTeamId(ownTeam.getId());
        ownMember = userRepository.saveAndFlush(ownMember);
        User staleForeignBinding = namedUser(
                "stats-group-path-stale-" + suffix + "@magti.ge", Role.OPERATOR,
                "ტექნიკური — " + ownGroupName, "Path stale " + suffix);
        staleForeignBinding.setTeamId(foreignTeam.getId());
        staleForeignBinding = userRepository.saveAndFlush(staleForeignBinding);

        mockMvc.perform(authed(get(
                        "/api/admin/departments/{department}/groups/{groupName}/users",
                        department.getName(), ownGroupName), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[?(@.user_id == " + ownMember.getId() + ")]").exists())
                .andExpect(jsonPath("$.users[?(@.user_id == " + staleForeignBinding.getId() + ")]").doesNotExist());

        mockMvc.perform(authed(get(
                        "/api/admin/departments/{department}/groups/{groupName}/users",
                        department.getName(), emptyAssignedGroupName), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(0));

        mockMvc.perform(authed(get(
                        "/api/admin/departments/{department}/groups/{groupName}/users",
                        department.getName(), foreignGroupName), tokenFor(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get(
                        "/api/admin/departments/{department}/groups/{groupName}/users",
                        department.getName(), "ჯგუფი missing-" + suffix), tokenFor(manager)))
                .andExpect(status().isForbidden());
    }

    @Test
    void activityTrendDayBucketReturnsRequestedNumberOfDaysWithCounts() throws Exception {
        User admin = createStatsViewer("stats-act-admin@magti.ge", "All");
        createAuditLog(admin.getId(), TbilisiTime.now(), AuditCategory.SYSTEM);

        mockMvc.perform(authed(get("/api/statistics/activity").param("days", "3"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[2].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void activityTrendRejectsInvalidBucket() throws Exception {
        User admin = createStatsViewer("stats-act-bad-admin@magti.ge", "All");

        mockMvc.perform(authed(get("/api/statistics/activity").param("bucket", "week"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("bucket must be 'day' or 'hour'"));
    }

    @Test
    void breakdownReturnsGroupedCountsForEachWhitelistedDimension() throws Exception {
        User admin = createStatsViewer("stats-brk-admin@magti.ge", "დეპარტამენტი-X");

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "department"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'დეპარტამენტი-X')]").exists());

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "role"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'content_admin')]").exists());
    }

    @Test
    void breakdownRejectsUnknownDimension() throws Exception {
        User admin = createStatsViewer("stats-brk-bad-admin@magti.ge", "All");

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "nonsense"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("dimension must be one of: department, role, status"));
    }
}
