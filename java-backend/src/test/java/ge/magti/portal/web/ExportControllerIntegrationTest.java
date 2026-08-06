package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Article;
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
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
        User user = new User();
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
    void csvExportRequiresSystemAdminAndScopesToEligibleUsersWithFormulaSanitized() throws Exception {
        User admin = createUser("exp-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User manager = createUser("exp-mgr1@magti.ge", Role.MANAGER, "All");
        User operator = createUser("exp-op1@magti.ge", Role.OPERATOR, "სავალდებულო განყოფილება " + System.nanoTime());
        operator.setName("=cmd|'/c calc'!A1");
        userRepository.saveAndFlush(operator);

        Article article = createArticle("სავალდებულო სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), operator.getDepartment());
        markRead(operator, reading);
        markRead(manager, reading);

        mockMvc.perform(authed(get("/api/export/readings"), tokenFor(manager)))
                .andExpect(status().isForbidden());

        String csv = mockMvc.perform(authed(get("/api/export/readings"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=readings_export.csv"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(csv.startsWith("User ID,User Name,Item Type,Item ID,Status,Read At\r\n"));
        assertTrue(csv.contains("'=cmd|'/c calc'!A1"), "operator's formula-leading name must be sanitized");
        assertFalse(csv.contains(manager.getId() + ","), "manager (management role) must be excluded from the eligible export");
    }

    @Test
    void xlsxExportBuildsAndDownloadsThenTheJobRowAndFileAreGone() throws Exception {
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
            assertEquals("თანამშრომელი", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(operator.getName(), wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }

        assertFalse(exportJobRepository.findById(jobId).isPresent(), "download must delete the job row");
        mockMvc.perform(authed(get("/api/export/status/" + jobId), tokenFor(admin)))
                .andExpect(status().isNotFound());
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
        User manager = createUser("exp-mgr3@magti.ge", Role.MANAGER, "All");
        User operator = createUser("exp-op5@magti.ge", Role.OPERATOR, "სტატისტიკის განყოფილება " + System.nanoTime());
        Article article = createArticle("სტატისტიკის სტატია " + System.nanoTime());
        RequiredReading reading = createReading(article.getId(), operator.getDepartment());
        markRead(operator, reading);

        String body = mockMvc.perform(authed(get("/api/export/team-stats.pdf"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(body).get("job_id").asText();

        byte[] pdfBytes = mockMvc.perform(authed(get("/api/export/download/" + jobId), tokenFor(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("გუნდის სტატისტიკა"));
            assertTrue(text.contains(operator.getDepartment()));
        }
    }

    @Test
    void statusAndDownloadReturn404ForUnknownJob() throws Exception {
        User admin = createUser("exp-admin3@magti.ge", Role.SYSTEM_ADMIN, "All");
        mockMvc.perform(authed(get("/api/export/status/does-not-exist"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("საექსპორტო დავალება ვერ მოიძებნა"));
        mockMvc.perform(authed(get("/api/export/download/does-not-exist"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ექსპორტი ჯერ არ არის მზად"));
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
