package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the real wiring, not just the logic: a real login through the
 * actual Spring Security filter chain, a real JWT, and {@code
 * @AuthenticationPrincipal} genuinely resolving to the authenticated
 * {@code User} inside AuditLogController -- the first use of that
 * annotation anywhere in this codebase, so it's verified against the real
 * chain rather than assumed to work. {@code @Transactional} rolls back the
 * JIT-provisioned users and the audit rows their logins create.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private String loginAndGetToken(String email, String ip) throws Exception {
        String body = mockMvc.perform(withIp(post("/api/auth/login"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("access_token").asText();
    }

    /** Builds a user with fine-grained permissions, unlike loginAndGetToken's qa_accounts.py defaults. */
    private User createUser(String email, Role role, String department, Set<Permission> extraPermissions) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი " + email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        Set<Permission> granted = new LinkedHashSet<>(Permission.defaultsFor(role));
        granted.addAll(extraPermissions);
        user.setPermissions(granted.stream().map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private AuditLog writeAuditRow(Long adminId, String action, String itemType, Long itemId, String details) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setItemType(itemType);
        log.setItemId(itemId);
        log.setDetails(details);
        log.setTimestamp(TbilisiTime.now());
        return auditLogRepository.saveAndFlush(log);
    }

    @Test
    void systemAdminCanVerifyTheAuditRowTheirOwnLoginJustCreated() throws Exception {
        String token = loginAndGetToken("admin@magti.ge", "10.20.0.1");

        Long adminId = userRepository.findByEmail("admin@magti.ge").orElseThrow().getId();
        Long loginRowId = auditLogRepository.findAll().stream()
                .filter(row -> adminId.equals(row.getAdminId()) && "LOGIN".equals(row.getAction()))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(get("/api/audit-logs/" + loginRowId + "/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.hash_match").value(true))
                .andExpect(jsonPath("$.chain_match").value(true));
    }

    @Test
    void verifyingMissingAuditIdIs404AndCannotExposeOtherRows() throws Exception {
        User admin = createUser("audit-missing-" + System.nanoTime() + "@magti.ge",
                Role.SYSTEM_ADMIN, "All", Set.of());
        mockMvc.perform(get("/api/audit-logs/999999999/verify")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ჩანაწერი ვერ მოიძებნა"));
    }

    @Test
    void systemAdminCanReadChainHealth() throws Exception {
        String token = loginAndGetToken("admin@magti.ge", "10.20.0.2");

        mockMvc.perform(get("/api/audit-logs/chain-health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void malformedChainWindowIsRejectedBeforeAuditQuery() throws Exception {
        User admin = createUser("audit-window-" + System.nanoTime() + "@magti.ge",
                Role.SYSTEM_ADMIN, "All", Set.of());
        mockMvc.perform(get("/api/audit-logs/chain-health?n=invalid")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("მოთხოვნის პარამეტრი არასწორია"));
    }

    @Test
    void managerIsForbiddenFromChainHealth() throws Exception {
        String token = loginAndGetToken("manager@magti.ge", "10.20.0.3");

        mockMvc.perform(get("/api/audit-logs/chain-health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("ეს ფუნქცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის"));
    }

    @Test
    void noTokenAtAllIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/audit-logs/chain-health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void listResolvesItemNameFromTheJoinedArticleAndReportsTotalCountHeader() throws Exception {
        User admin = createUser("audit.admin1@magti.ge", Role.SYSTEM_ADMIN, "All", Set.of());
        Article article = new Article();
        article.setTitle("სატესტო სტატია");
        article.setContent("შინაარსი");
        article.setVersion(1);
        articleRepository.saveAndFlush(article);
        writeAuditRow(admin.getId(), "UPDATE", "article", article.getId(), null);
        writeAuditRow(admin.getId(), "UPDATE", "article", article.getId(), null);

        mockMvc.perform(get("/api/audit-logs")
                        .param("user_id", String.valueOf(admin.getId()))
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].item_name").value("სატესტო სტატია"))
                .andExpect(jsonPath("$[0].admin_name").value(admin.getName()));
    }

    @Test
    void managerIsScopedToTheirExactDepartmentNotThePrefix() throws Exception {
        // Unique-per-run department strings, not the fixed "ტექნიკური —
        // ჯგუფი 01/02" literal: this dev Oracle schema is shared and
        // accumulates real audit rows for those exact real department
        // names from months of manual verification, which inflated
        // X-Total-Count beyond this test's own 1 expected row (confirmed
        // root cause, not a code bug). A unique suffix makes the exact-
        // match assertion below independent of whatever else is in the
        // table.
        String uniqueSuffix = "-audittest-" + System.nanoTime();
        String group01Dept = "ტექნიკური — ჯგუფი 01" + uniqueSuffix;
        String group02Dept = "ტექნიკური — ჯგუფი 02" + uniqueSuffix;
        User group01Actor = createUser("audit.group01actor@magti.ge", Role.OPERATOR, group01Dept, Set.of());
        User group02Actor = createUser("audit.group02actor@magti.ge", Role.OPERATOR, group02Dept, Set.of());
        writeAuditRow(group01Actor.getId(), "LOGIN", "user", group01Actor.getId(), null);
        writeAuditRow(group02Actor.getId(), "LOGIN", "user", group02Actor.getId(), null);

        User group01Manager = createUser("audit.group01manager@magti.ge", Role.MANAGER,
                group01Dept, Set.of());

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(group01Manager)))
                .andExpect(status().isForbidden());
    }

    /**
     * SEC-13, the other half of the rule. The manager above is stored as a
     * sub-group and must stay pinned to it. This one is stored as the bare
     * parent, and used to see nothing at all -- the department pin was an
     * exact string match, so "ტექნიკური" matched no row belonging to
     * "ტექნიკური — ჯგუფი NN".
     */
    @Test
    void parentDepartmentManagerSeesTheirSubGroupsRatherThanNothing() throws Exception {
        String uniqueSuffix = "-parentaudit-" + System.nanoTime();
        String parentDept = "ტექნიკური" + uniqueSuffix;
        String childDept = parentDept + " — ჯგუფი 07";
        String foreignDept = "ოფისი" + uniqueSuffix;

        User childActor = createUser("audit.childactor@magti.ge", Role.OPERATOR, childDept, Set.of());
        User foreignActor = createUser("audit.foreignactor@magti.ge", Role.OPERATOR, foreignDept, Set.of());
        writeAuditRow(childActor.getId(), "LOGIN", "user", childActor.getId(), null);
        writeAuditRow(foreignActor.getId(), "LOGIN", "user", foreignActor.getId(), null);

        User parentManager = createUser("audit.parentmanager@magti.ge", Role.MANAGER,
                parentDept, Set.of());

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(parentManager)))
                .andExpect(status().isForbidden());
    }

    /**
     * A manager whose department was never filled in used to return null
     * from the scope helper -- and null is how that helper says
     * "unrestricted". So the account with the least-defined scope got the
     * widest view of the audit trail. Nullable column, reachable state.
     */
    @Test
    void managerWithNoDepartmentSeesNoAuditRowsRatherThanAllOfThem() throws Exception {
        User actor = createUser("audit.someactor@magti.ge", Role.OPERATOR,
                "ტექნიკური — ჯგუფი 09-nodept-" + System.nanoTime(), Set.of());
        writeAuditRow(actor.getId(), "LOGIN", "user", actor.getId(), null);

        User unassignedManager = createUser("audit.nodeptmanager@magti.ge", Role.MANAGER,
                null, Set.of());

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(unassignedManager)))
                .andExpect(status().isForbidden());
    }

    @Test
    void qFreeTextSearchMatchesAgainstDetailsColumn() throws Exception {
        User admin = createUser("audit.admin2@magti.ge", Role.SYSTEM_ADMIN, "All", Set.of());
        writeAuditRow(admin.getId(), "CREATE_USER", "user", admin.getId(), "შეიცავს უნიკალურ-სიტყვას-XYZ");
        writeAuditRow(admin.getId(), "CREATE_USER", "user", admin.getId(), "სხვა დეტალები");

        mockMvc.perform(get("/api/audit-logs")
                        .param("q", "უნიკალურ-სიტყვას")
                        .param("user_id", String.valueOf(admin.getId()))
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].details").value("შეიცავს უნიკალურ-სიტყვას-XYZ"));
    }

    @Test
    void malformedAuditUserFilterIsRejectedBeforeQuery() throws Exception {
        User admin = createUser("audit.invalidfilter-" + System.nanoTime() + "@magti.ge",
                Role.SYSTEM_ADMIN, "All", Set.of());

        mockMvc.perform(get("/api/audit-logs")
                        .param("user_id", "not-a-number")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actionFilterLoginAggregatesPasswordAndSsoLogins() throws Exception {
        User admin = createUser("audit.admin3@magti.ge", Role.SYSTEM_ADMIN, "All", Set.of());
        writeAuditRow(admin.getId(), "LOGIN", "user", admin.getId(), null);
        writeAuditRow(admin.getId(), "LOGIN_SSO", "user", admin.getId(), null);
        writeAuditRow(admin.getId(), "LOGOUT", "user", admin.getId(), null);

        mockMvc.perform(get("/api/audit-logs")
                        .param("action", "LOGIN")
                        .param("user_id", String.valueOf(admin.getId()))
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"));
    }

    @Test
    void listWritesAViewMetaAuditRowAfterBuildingTheResponse() throws Exception {
        User admin = createUser("audit.admin4@magti.ge", Role.SYSTEM_ADMIN, "All", Set.of());

        mockMvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk());

        List<AuditLog> metaRows = auditLogRepository.findAll().stream()
                .filter(row -> "VIEW_AUDIT_LOG".equals(row.getAction()) && admin.getId().equals(row.getAdminId()))
                .toList();
        assertEquals(1, metaRows.size());
        assertEquals("audit_log", metaRows.get(0).getItemType());
        assertEquals(admin.getId(), metaRows.get(0).getItemId());
        var details = new ObjectMapper().readTree(metaRows.get(0).getDetails());
        assertEquals(1, details.get("schema_version").asInt());
        assertEquals("SUCCESS", details.get("result").asText());
        assertTrue(details.get("before").isNull());
    }

    @Test
    void exportStreamsFormulaInjectionSanitizedCsvForSystemAdmin() throws Exception {
        User contentAdmin = createUser("audit.contentadmin@magti.ge", Role.SYSTEM_ADMIN, "All", Set.of());
        writeAuditRow(contentAdmin.getId(), "UPDATE", "system", 0L, "=cmd|'/c calc'!A1");

        String csv = mockMvc.perform(get("/api/audit-logs/export")
                        .header("Authorization", "Bearer " + tokenFor(contentAdmin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=audit_logs.csv"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(csv.startsWith("ID,დრო,ვინ,ქმედება,ობიექტი,დეტალები\r\n"));
        assertTrue(csv.contains("'=cmd"));

        List<AuditLog> metaRows = auditLogRepository.findAll().stream()
                .filter(row -> "EXPORT_AUDIT_LOG".equals(row.getAction())
                        && contentAdmin.getId().equals(row.getAdminId()))
                .toList();
        assertEquals(1, metaRows.size());
    }

    @Test
    void exportIsForbiddenForAManagerEvenWithThePermission() throws Exception {
        User manager = createUser("audit.exportmanager@magti.ge", Role.MANAGER, "All", Set.of());

        mockMvc.perform(get("/api/audit-logs/export")
                        .header("Authorization", "Bearer " + tokenFor(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("ეს ფუნქცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის"));
    }

    @Test
    void malformedExportUserFilterReturns400WithoutCsvOrMetaAudit() throws Exception {
        User admin = createUser("audit-export-invalid-" + System.nanoTime() + "@magti.ge",
                Role.SYSTEM_ADMIN, "All", Set.of());
        long auditBefore = auditLogRepository.count();

        mockMvc.perform(get("/api/audit-logs/export?user_id=not-a-number")
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.detail").value("მოთხოვნის პარამეტრი არასწორია"));
        assertEquals(auditBefore, auditLogRepository.count());
    }
}
