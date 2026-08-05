package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditCategory;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.SearchLog;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

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
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
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
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));
        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/manager/department-stats"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void kpiCountsReflectSeededData() throws Exception {
        User admin = createUser("stats-kpi-admin@magti.ge", Role.CONTENT_ADMIN, "All");
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
        // Existence-filter, not a positional [0] check -- both endpoints aggregate
        // across ALL search_logs (no user/department scoping in Python either), so
        // real seeded search volume on this Oracle instance may outrank this term.
        User admin = createUser("stats-search-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User op = createUser("stats-search-op@magti.ge", Role.OPERATOR, "All");
        // Georgian script has no case distinction, so the two variants below only
        // exercise TRIM (whitespace normalisation) -- confirmed matching Oracle's
        // actual TRIM/LOWER/GROUP BY behavior via a direct sqlplus check.
        String uniqueTerm = "უნიკალური-ძებნა-" + System.nanoTime();
        createSearchLog(op.getId(), uniqueTerm, true);
        createSearchLog(op.getId(), "  " + uniqueTerm + "  ", true);
        String uniqueFailedTerm = "წარუმატებელი-ძებნა-" + System.nanoTime();
        createSearchLog(op.getId(), uniqueFailedTerm, false);

        mockMvc.perform(authed(get("/api/statistics/popular-searches"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.search_term == '" + uniqueTerm.toLowerCase() + "')].count")
                        .value(org.hamcrest.Matchers.contains(2)));

        mockMvc.perform(authed(get("/api/statistics/failed-searches"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.search_term == '" + uniqueFailedTerm.toLowerCase() + "')].count")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void complianceStatisticsReturnsWellFormedPercentagesAndArticleList() throws Exception {
        // compute_compliance() is org-wide (routers/stats.py:161), so this asserts
        // shape/bounds rather than an exact figure -- real seeded data on this
        // Oracle instance already contributes to the numerator/denominator.
        User admin = createUser("stats-comp-admin@magti.ge", Role.CONTENT_ADMIN, "All");
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
    void adminTeamStatsScopesByTeamIdAndComputesAverage() throws Exception {
        User admin = createUser("stats-team-admin@magti.ge", Role.CONTENT_ADMIN, "All");
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

    @Test
    void criticalOperatorsListsUsersBelowThreshold() throws Exception {
        User admin = createUser("stats-crit-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User laggingOp = createUser("stats-crit-op@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle("კრიტიკული სტატია");
        createReading(article.getId(), "All"); // not read by anyone -> 0%

        mockMvc.perform(authed(get("/api/admin/critical-operators"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[?(@.user_id == " + laggingOp.getId() + ")]").exists())
                .andExpect(jsonPath("$.generated_at").exists());
    }

    @Test
    void groupUsersDrillDownMatchesDepartmentBucketAndGroupLabel() throws Exception {
        // Uses a made-up, unlikely-to-collide group suffix rather than asserting
        // an exact list size, since get_group_users scopes by department bucket
        // + exact group label across ALL active users on this Oracle instance.
        User admin = createUser("stats-grp-admin@magti.ge", Role.CONTENT_ADMIN, "All");
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
    }

    @Test
    void activityTrendDayBucketReturnsRequestedNumberOfDaysWithCounts() throws Exception {
        User admin = createUser("stats-act-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        createAuditLog(admin.getId(), TbilisiTime.now(), AuditCategory.SYSTEM);

        mockMvc.perform(authed(get("/api/statistics/activity").param("days", "3"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[2].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void activityTrendRejectsInvalidBucket() throws Exception {
        User admin = createUser("stats-act-bad-admin@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(get("/api/statistics/activity").param("bucket", "week"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("bucket must be 'day' or 'hour'"));
    }

    @Test
    void breakdownReturnsGroupedCountsForEachWhitelistedDimension() throws Exception {
        User admin = createUser("stats-brk-admin@magti.ge", Role.CONTENT_ADMIN, "დეპარტამენტი-X");

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "department"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'დეპარტამენტი-X')]").exists());

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "role"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'content_admin')]").exists());
    }

    @Test
    void breakdownRejectsUnknownDimension() throws Exception {
        User admin = createUser("stats-brk-bad-admin@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(get("/api/statistics/breakdown").param("dimension", "nonsense"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("dimension must be one of: department, role, status"));
    }
}
