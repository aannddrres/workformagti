package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.ExportJobCleanupScheduler;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- covers all 6
 * routers/exports.py endpoints. The async xlsx/pdf builds run through a
 * {@link SyncTaskExecutor} override ({@link SyncAsyncConfig}) so {@code
 * @Async} executes inline on the test's own thread/transaction instead of a
 * separate worker thread -- otherwise the worker's DB writes would run on a
 * different connection than this test's uncommitted, soon-to-be-rolled-back
 * transaction and could never see the just-created ExportJob row.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExportControllerIntegrationTest.SyncAsyncConfig.class)
@Transactional
class ExportControllerIntegrationTest {

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
    private ExportJobRepository exportJobRepository;
    @Autowired
    private ExportJobCleanupScheduler exportJobCleanupScheduler;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PortalProperties portalProperties;
    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class SyncAsyncConfig {
        @Bean
        AsyncConfigurer asyncConfigurer() {
            return new AsyncConfigurer() {
                @Override
                public Executor getAsyncExecutor() {
                    return new SyncTaskExecutor();
                }
            };
        }
    }

    @AfterEach
    void cleanupExportedFiles() throws IOException {
        Path dir = Path.of(portalProperties.getUploadsDir(), "exports");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> !p.equals(dir)).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private User createUser(String email, Role role, String department) {
        // find-or-update rather than blind insert: this suite's fixed emails
        // (exp-op2@magti.ge etc.) can already exist as leftover rows from an
        // earlier dev/session run against the shared Oracle instance -- same
        // cross-test-leakage class already fixed in the Category/Audit suites.
        User user = userRepository.findByEmail(email).orElseGet(User::new);
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი " + email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
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

    private Article createArticle(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        return articleRepository.saveAndFlush(article);
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

    private void markRead(User user, RequiredReading reading) {
        ReadStatus stat = new ReadStatus();
        stat.setUserId(user.getId());
        stat.setRequiredReadingId(reading.getId());
        stat.setStatus("read");
        stat.setReadAt(TbilisiTime.now());
        stat.setOperatorDepartmentSnapshot(user.getDepartment());
        readStatusRepository.saveAndFlush(stat);
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/export/readings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void csvExportRequiresReportsExportPermissionAndScopesToEligibleUsersWithFormulaSanitized() throws Exception {
        // Bug #313 fix: CSV/XLSX used to be system_admin-only while PDF (the
        // exact same data) used the broader reports.export permission -- a
        // manager could get PDF but was denied CSV/XLSX. Reconciled onto
        // reports.export for all three, so manager is now allowed here too;
        // operator (no reports.export by default) is the denial case instead.
        User admin = createUser("exp-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User manager = createUser("exp-mgr1@magti.ge", Role.MANAGER, "All");
        User operator = createUser("exp-op1@magti.ge", Role.OPERATOR, "სავალდებულო განყოფილება " + System.nanoTime());
        operator.setName("=cmd|'/c calc'!A1");
        userRepository.saveAndFlush(operator);

        Article article = createArticle("სავალდებულო სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), operator.getDepartment());
        markRead(operator, reading);
        markRead(manager, reading);

        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(manager)))
                .andExpect(status().isOk());

        String csv = mockMvc.perform(authed(get("/api/export/readings"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=readings_export.csv"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(csv.startsWith("User ID,User Name,Item Type,Item ID,Status,Read At\r\n"));
        assertTrue(csv.contains("'=cmd|'/c calc'!A1"), "operator's formula-leading name must be sanitized");
        assertFalse(csv.contains(manager.getId() + ","), "manager (management role) must be excluded from the eligible export");
    }

    /**
     * BL-09 acceptance: the same job id must still download the second time.
     * This test previously asserted the opposite -- {@code "download must
     * delete the job row"} -- which is exactly the behaviour the audit
     * flagged: a refresh, a retry or an interrupted transfer destroyed the
     * export and then reported "not ready yet".
     */
    @Test
    void xlsxExportDownloadsTwiceAndKeepsItsJobRow() throws Exception {
        User admin = createUser("exp-admin2@magti.ge", Role.SYSTEM_ADMIN, "All");
        User operator = createUser("exp-op2@magti.ge", Role.OPERATOR, "ექსელის განყოფილება " + System.nanoTime());
        Article article = createArticle("ექსელის სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), operator.getDepartment());
        markRead(operator, reading);

        String body = mockMvc.perform(authed(get("/api/export/readings.xlsx"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(body).get("job_id").asText();

        String statusBody = mockMvc.perform(authed(get("/api/export/status/" + jobId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("completed", objectMapper.readTree(statusBody).get("status").asText());

        byte[] xlsxBytes = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            var sheet = wb.getSheetAt(0);
            assertEquals("თანამშრომელი", sheet.getRow(0).getCell(0).getStringCellValue());
            // eligibleReadingRows(admin) is system-wide for an unscoped role
            // (SEC-02 pins only MANAGER), so it is not scoped to this test's
            // reading either -- the shared dev Oracle instance has real accumulated
            // usage data, so this test's row can land anywhere, not just row
            // 1. Search for it instead of assuming position (same
            // cross-test-leakage class already fixed in Category/Audit).
            boolean foundOperatorRow = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null
                        && operator.getName().equals(row.getCell(0).getStringCellValue())) {
                    foundOperatorRow = true;
                    break;
                }
            }
            assertTrue(foundOperatorRow, "exported xlsx must contain this test's operator row");
        }

        byte[] secondDownload = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(xlsxBytes, secondDownload, "a second download of the same job id must return the same file");

        assertTrue(exportJobRepository.findById(jobId).isPresent(), "download must not delete the job row");
        mockMvc.perform(authed(get("/api/export/status/" + jobId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void pdfExportRequiresReportsExportPermissionAndRendersGeorgianText() throws Exception {
        User operatorNoPerm = createUser("exp-op3@magti.ge", Role.OPERATOR, "All");
        User manager = createUser("exp-mgr2@magti.ge", Role.MANAGER, "პდფ განყოფილება " + System.nanoTime());
        Article article = createArticle("პდფ სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), manager.getDepartment());
        // Manager itself is a management role (excluded from eligibility) --
        // use a separate eligible operator so the export has a data row.
        User eligibleOperator = createUser("exp-op4@magti.ge", Role.OPERATOR, manager.getDepartment());
        markRead(eligibleOperator, reading);

        mockMvc.perform(authed(get("/api/export/readings.pdf"), tokenFor(operatorNoPerm)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));

        String body = mockMvc.perform(authed(get("/api/export/readings.pdf"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(body).get("job_id").asText();

        byte[] pdfBytes = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("სავალდებულოდ"));
            assertTrue(text.contains(eligibleOperator.getName()));
        }
    }

    @Test
    void teamStatsPdfAggregatesByDepartmentAndIsDownloadable() throws Exception {
        // SEC-02: the caller here used to be a MANAGER whose own department was
        // "All", which only aggregated across departments because the query was
        // org-wide for everyone. Now that a manager is pinned to their own
        // department, cross-department aggregation is by definition the
        // unscoped roles' view -- so this asserts it as system_admin, which is
        // also the "admin behaviour unchanged" half of the SEC-02 acceptance.
        User admin = createUser("exp-admin4@magti.ge", Role.SYSTEM_ADMIN, "All");
        User operator = createUser("exp-op5@magti.ge", Role.OPERATOR, "სტატისტიკის განყოფილება " + System.nanoTime());
        Article article = createArticle("სტატისტიკის სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), operator.getDepartment());
        markRead(operator, reading);

        String body = mockMvc.perform(authed(get("/api/export/team-stats.pdf"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(body).get("job_id").asText();

        byte[] pdfBytes = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("გუნდის სტატისტიკა"));
            assertTrue(text.contains(operator.getDepartment()));
        }
    }

    /**
     * SEC-02 regression guard. Before the fix,
     * {@code ExportQueryService.eligibleReadingRows()} took no caller at all
     * and every export was org-wide, so this assertion on {@code otherDept}'s
     * operator failed for the manager (their row was present) while passing
     * for the admin.
     */
    @Test
    void managerReadingsExportIsPinnedToTheirOwnDepartment() throws Exception {
        String ownDept = "ექსპორტის განყოფილება " + System.nanoTime();
        String otherDept = "სხვისი განყოფილება " + System.nanoTime();

        User manager = createUser("exp-scope-mgr@magti.ge", Role.MANAGER, ownDept);
        User ownOperator = createUser("exp-scope-own@magti.ge", Role.OPERATOR, ownDept);
        User otherOperator = createUser("exp-scope-other@magti.ge", Role.OPERATOR, otherDept);
        User admin = createUser("exp-scope-admin@magti.ge", Role.SYSTEM_ADMIN, "All");

        Article article = createArticle("სკოუპის სტატია " + System.nanoTime());
        markRead(ownOperator, createReading(article.getId(), ownDept));
        markRead(otherOperator, createReading(article.getId(), otherDept));

        Set<String> managerIds = csvUserIds(mockMvc.perform(authed(get("/api/export/readings"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(managerIds.contains(String.valueOf(ownOperator.getId())),
                "manager must still see their own department's operator");
        assertFalse(managerIds.contains(String.valueOf(otherOperator.getId())),
                "SEC-02: manager's export must not carry another department's operator");

        // system_admin behaviour is unchanged: still org-wide.
        Set<String> adminIds = csvUserIds(mockMvc.perform(authed(get("/api/export/readings"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(adminIds.contains(String.valueOf(ownOperator.getId())));
        assertTrue(adminIds.contains(String.valueOf(otherOperator.getId())),
                "system_admin must keep the unscoped org-wide export");
    }

    /**
     * SEC-02, second query method: {@code departmentComplianceTotals()} was
     * org-wide too, so a manager's team-stats PDF was a company-wide league
     * table of every department's compliance percentage.
     */
    @Test
    void managerTeamStatsPdfCoversOnlyTheirOwnDepartment() throws Exception {
        String ownDept = "სტატის განყოფილება " + System.nanoTime();
        String otherDept = "უცხო განყოფილება " + System.nanoTime();

        User manager = createUser("exp-scope-mgr2@magti.ge", Role.MANAGER, ownDept);
        User ownOperator = createUser("exp-scope-own2@magti.ge", Role.OPERATOR, ownDept);
        User otherOperator = createUser("exp-scope-other2@magti.ge", Role.OPERATOR, otherDept);

        Article article = createArticle("სტატის სტატია " + System.nanoTime());
        markRead(ownOperator, createReading(article.getId(), ownDept));
        markRead(otherOperator, createReading(article.getId(), otherDept));

        String body = mockMvc.perform(authed(get("/api/export/team-stats.pdf"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(body).get("job_id").asText();

        byte[] pdfBytes = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains(ownDept), "manager's own department row must still be present");
            assertFalse(text.contains(otherDept),
                    "SEC-02: manager's team-stats PDF must not carry another department's totals");
        }
    }

    /**
     * SEC-02: the export audit row records the effective scope, so the trail
     * can distinguish a team-scoped download from an org-wide one. Same
     * {@code scope_department} key AuditLogController.writeMetaAudit uses.
     */
    @Test
    void exportAuditRowRecordsTheEffectiveScope() throws Exception {
        String ownDept = "აუდიტის განყოფილება " + System.nanoTime();
        User manager = createUser("exp-scope-mgr3@magti.ge", Role.MANAGER, ownDept);
        User admin = createUser("exp-scope-admin2@magti.ge", Role.SYSTEM_ADMIN, "All");

        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(manager))).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(admin))).andExpect(status().isOk());

        assertEquals(ownDept, latestExportScope(manager.getId()),
                "a manager's export must be audited as scoped to their department");
        assertEquals(ExportController.SCOPE_ALL, latestExportScope(admin.getId()),
                "an unscoped role's export must be audited as org-wide");
    }

    /**
     * The end-to-end half of {@code ExportControllerScopeGateTest}: over real
     * HTTP, with the permission genuinely persisted on a real row, granting
     * {@code reports.export} to someone who leads nobody produces no export
     * and no audit row -- not a smaller one.
     */
    @Test
    void aGrantedExportPermissionWithoutLeadershipIsRefusedAndUnaudited() throws Exception {
        String ownDept = "ტექნიკური — ექსპორტის ჯგუფი " + System.nanoTime();
        User contentAdmin = createUser("exp-scope-content@magti.ge", Role.CONTENT_ADMIN, ownDept);
        contentAdmin.getPermissions().add(Permission.REPORTS_EXPORT.value());
        userRepository.saveAndFlush(contentAdmin);

        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(contentAdmin)))
                .andExpect(status().isForbidden());

        assertEquals(0L, exportAuditRowCount(contentAdmin.getId()),
                "a refused export must leave no audit row claiming it happened");
    }

    /** First CSV field of every data row = User ID (see the exportReadingsCsv header). */
    private static Set<String> csvUserIds(String csv) {
        Set<String> ids = new LinkedHashSet<>();
        String[] lines = csv.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            ids.add(lines[i].split(",")[0].replace("\"", "").strip());
        }
        return ids;
    }

    private long exportAuditRowCount(Long adminId) {
        return entityManager.createQuery(
                        "select count(a) from AuditLog a where a.adminId = :adminId and a.action = 'EXPORT'", Long.class)
                .setParameter("adminId", adminId)
                .getSingleResult();
    }

    private String latestExportScope(Long adminId) throws Exception {
        List<AuditLog> rows = entityManager.createQuery(
                        "select a from AuditLog a where a.adminId = :adminId and a.action = 'EXPORT' "
                                + "order by a.id desc", AuditLog.class)
                .setParameter("adminId", adminId)
                .setMaxResults(1)
                .getResultList();
        assertFalse(rows.isEmpty(), "the export must have written an audit row");
        JsonNode details = objectMapper.readTree(rows.get(0).getDetails());
        return details.path("scope_department").asText(null);
    }

    /**
     * BL-09: download used to answer an unknown id with the same "not ready
     * yet" 404 it used for a job that really was still building, so the
     * message told the user to wait for something that would never arrive.
     * An unknown id and a swept row are indistinguishable at this point and
     * both mean "regenerate", so both are 410.
     */
    @Test
    void unknownJobIsNotFoundOnStatusAndGoneOnDownload() throws Exception {
        User admin = createUser("exp-admin3@magti.ge", Role.SYSTEM_ADMIN, "All");
        mockMvc.perform(authed(get("/api/export/status/does-not-exist"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("საექსპორტო დავალება ვერ მოიძებნა"));
        mockMvc.perform(authed(get("/api/export/download/does-not-exist"), tokenFor(admin)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("expired"));
    }

    @Test
    void cleanupSchedulerRemovesExpiredJobsAndTheirFiles() throws Exception {
        Path dir = Path.of(portalProperties.getUploadsDir(), "exports");
        Files.createDirectories(dir);
        Path staleFile = dir.resolve("export_stale-job.pdf");
        Files.write(staleFile, "not a real pdf".getBytes());

        ExportJob stale = new ExportJob();
        stale.setId("stale-job-" + System.nanoTime());
        stale.setStatus("completed");
        stale.setPath(staleFile.toString());
        stale.setExpiresAt(System.currentTimeMillis() / 1000.0 - 60);
        exportJobRepository.saveAndFlush(stale);

        exportJobCleanupScheduler.sweepExpiredJobs();

        assertFalse(exportJobRepository.findById(stale.getId()).isPresent());
        assertFalse(Files.exists(staleFile), "expired export file should be deleted by the sweep");
    }
}
