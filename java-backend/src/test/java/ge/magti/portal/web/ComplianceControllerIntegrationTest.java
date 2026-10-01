package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain. Covers all 7
 * Compliance endpoints plus the non-obvious behaviours: quiz-gate 403 on
 * mark-read, cross-department 403, overdue-status derivation, the
 * read-receipt bridge, and the required-reading notification fan-out.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ComplianceControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository permissionOverrideRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository articleTargetDepartmentRepository;
    @Autowired
    private VideoInstructionRepository videoInstructionRepository;
    @Autowired
    private ArticleReadReceiptRepository articleReadReceiptRepository;
    @Autowired
    private QuizAttemptRepository quizAttemptRepository;
    @Autowired
    private ReminderRepository reminderRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @PersistenceContext
    private EntityManager entityManager;

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

    private Article createArticle(String title, boolean quizEnabled) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        article.setQuizEnabled(quizEnabled);
        // The entity defaults is_draft to true: without this every fixture
        // was an authorless private draft, which nobody may assign (PO-34).
        article.setDraft(false);
        // And published, for everyone: an article operators cannot open binds
        // nobody (PO-40), and these fixtures used to be exactly that -- status
        // "draft", no audience -- assigned all the same.
        article.setStatus("published");
        Article saved = articleRepository.saveAndFlush(article);
        ArticleTargetDepartment everyone = new ArticleTargetDepartment();
        everyone.setArticleId(saved.getId());
        everyone.setDepartment("All");
        articleTargetDepartmentRepository.saveAndFlush(everyone);
        return saved;
    }

    private VideoInstruction createVideo(String title, String url) {
        VideoInstruction video = new VideoInstruction();
        video.setTitle(title);
        video.setVideoUrl(url);
        video.setTargetDepartment("All");
        return videoInstructionRepository.saveAndFlush(video);
    }

    private RequiredReading createReading(String itemType, Long itemId, String targetDepartment, OffsetDateTime dueDate) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType(itemType);
        reading.setItemId(itemId);
        reading.setTargetDepartment(targetDepartment);
        reading.setDueDate(dueDate);
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

    private String requiredReadingJson(String itemType, long itemId, String dept, String dueIso) {
        return "{\"item_type\":\"" + itemType + "\",\"item_id\":" + itemId + ",\"target_department\":\""
                + dept + "\",\"due_date\":\"" + dueIso + "\",\"priority\":\"high\"}";
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/compliance/my-readings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorCannotCreateRequiredReading() throws Exception {
        User operator = createUser("comp-op1@magti.ge", Role.OPERATOR, "All");
        long readingsBefore = requiredReadingRepository.count();

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", 1, "All", "2030-01-01T00:00:00+04:00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        assertEquals(readingsBefore, requiredReadingRepository.count());
    }

    @Test
    void missingDueDateCannotCreateRequiredReadingOrAudit() throws Exception {
        User admin = createUser("comp-invalid-" + System.nanoTime() + "@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle("ვალდებულების გარეშე", false);
        long readingsBefore = requiredReadingRepository.count();
        long auditBefore = auditLogRepository.count();

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + article.getId()
                                + ",\"target_department\":\"All\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(readingsBefore, requiredReadingRepository.count());
        assertEquals(auditBefore, auditLogRepository.count());
    }

    @Test
    void adminRequiredReadingCrudLifecycleAndByItem() throws Exception {
        User admin = createUser("comp-admin1@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle("სავალდებულო სტატია", false);

        String createBody = mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "All", "2030-06-01T00:00:00+04:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_type").value("article"))
                .andExpect(jsonPath("$.priority").value("high"))
                .andReturn().getResponse().getContentAsString();
        long readingId = objectMapper.readTree(createBody).get("id").asLong();

        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) readingId));

        mockMvc.perform(authed(put("/api/compliance/required-readings/" + readingId), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "ოფისი", "2031-01-01T00:00:00+04:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_department").value("ოფისი"));

        mockMvc.perform(authed(delete("/api/compliance/required-readings/" + readingId), tokenFor(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));

        List<String> actions = auditLogRepository.findAll().stream()
                .filter(a -> "required_reading".equals(a.getItemType())
                        && Long.valueOf(readingId).equals(a.getItemId()))
                .map(a -> a.getAction())
                .toList();
        assertTrue(actions.containsAll(List.of(
                "CREATE_REQUIRED_READING", "UPDATE_REQUIRED_READING", "DELETE_REQUIRED_READING")));
        for (var audit : auditLogRepository.findAll().stream()
                .filter(a -> "required_reading".equals(a.getItemType())
                        && Long.valueOf(readingId).equals(a.getItemId()))
                .toList()) {
            var details = objectMapper.readTree(audit.getDetails());
            assertEquals(1, details.get("schema_version").asInt());
            assertEquals("SUCCESS", details.get("result").asText());
        }
    }

    @Test
    void byItemLookupRejectsAnUnbindableItemId() throws Exception {
        User admin = createUser("by-item-invalid-admin@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/notanumber"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("მოთხოვნის პარამეტრი არასწორია"));
    }

    @Test
    void myReadingsShowsOverdueAndReadStatuses() throws Exception {
        User operator = createUser("comp-op2@magti.ge", Role.OPERATOR, "ოფისი");
        Article overdueArticle = createArticle("ვადაგადაცილებული", false);
        Article doneArticle = createArticle("წაკითხული", false);
        RequiredReading overdue = createReading("article", overdueArticle.getId(), "ოფისი", TbilisiTime.now().minusDays(2));
        RequiredReading done = createReading("article", doneArticle.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        markReadDirect(operator, done);

        String body = mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = objectMapper.readTree(body);
        java.util.Map<Long, com.fasterxml.jackson.databind.JsonNode> byReadingId = new java.util.HashMap<>();
        arr.forEach(n -> byReadingId.put(n.get("reading").get("id").asLong(), n));

        assertEquals("overdue", byReadingId.get(overdue.getId()).get("status").asText());
        assertTrue(byReadingId.get(overdue.getId()).get("is_overdue").asBoolean());
        assertEquals("ვადაგადაცილებული", byReadingId.get(overdue.getId()).get("item_title").asText());
        assertEquals("read", byReadingId.get(done.getId()).get("status").asText());
        assertTrue(!byReadingId.get(done.getId()).get("is_overdue").asBoolean());
    }

    @Test
    void myReadingsIsEmptyForManagementRoles() throws Exception {
        User manager = createUser("comp-mgr@magti.ge", Role.MANAGER, "ოფისი");
        Article article = createArticle("მენეჯერს არ უჩანს", false);
        createReading("article", article.getId(), "All", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    /**
     * A reading of an item that no longer exists used to be listed with an
     * "Item #id" placeholder, owed like any other. PO-40: nobody owes what
     * nobody can open, so it is left out; its record stays.
     */
    @Test
    void myReadingsResolvesVideoContentAndLeavesOutAMissingItem() throws Exception {
        User operator = createUser("comp-op3@magti.ge", Role.OPERATOR, "All");
        VideoInstruction video = createVideo("ვიდეო ინსტრუქცია", "https://youtu.be/xyz");
        RequiredReading videoReading = createReading("video", video.getId(), "All", TbilisiTime.now().plusDays(5));
        RequiredReading ghostReading = createReading("article", 999999999L, "All", TbilisiTime.now().plusDays(5));

        String body = mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = objectMapper.readTree(body);
        java.util.Map<Long, com.fasterxml.jackson.databind.JsonNode> byReadingId = new java.util.HashMap<>();
        arr.forEach(n -> byReadingId.put(n.get("reading").get("id").asLong(), n));

        assertEquals("ვიდეო ინსტრუქცია", byReadingId.get(videoReading.getId()).get("item_title").asText());
        assertEquals("https://youtu.be/xyz", byReadingId.get(videoReading.getId()).get("item_content").asText());
        assertTrue(!byReadingId.containsKey(ghostReading.getId()), "a missing item binds nobody");
        assertTrue(requiredReadingRepository.findById(ghostReading.getId()).isPresent());
    }

    @Test
    void myProgressComputesOnlyTheCallersPercentage() throws Exception {
        User operator = createUser("comp-op4@magti.ge", Role.OPERATOR, "ოფისი");
        User otherOperator = createUser("comp-op4-other@magti.ge", Role.OPERATOR, "ოფისი");
        Article a1 = createArticle("პირველი", false);
        Article a2 = createArticle("მეორე", false);
        RequiredReading r1 = createReading("article", a1.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        createReading("article", a2.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        markReadDirect(operator, r1);

        mockMvc.perform(authed(get("/api/compliance/my-progress"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_mandatory").value(2))
                .andExpect(jsonPath("$.read_completed").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.percentage").value(50));

        mockMvc.perform(authed(get("/api/compliance/my-progress"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_mandatory").value(2))
                .andExpect(jsonPath("$.read_completed").value(0))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.percentage").value(0));
    }

    /**
     * Simulation, 2026-10-01: after a mandatory article's text changed, the
     * reader saw "changed" with no way to confirm the new version. Confirming
     * again now records the new version's receipt and clears the flag; the
     * first version's receipt stays as evidence.
     */
    @Test
    void confirmingAgainAfterAnEditAcknowledgesTheNewVersion() throws Exception {
        User operator = createUser("comp-reack@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("შეცვლილი სტატია", false);
        RequiredReading reading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isOk());

        Article edited = articleRepository.findById(article.getId()).orElseThrow();
        edited.setVersion(2);
        edited.setContent("ახალი ტექსტი");
        edited.setUpdatedAt(TbilisiTime.now());
        articleRepository.saveAndFlush(edited);

        mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reading.id == " + reading.getId() + ")].changed_since_read").value(true));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(operator)))
                .andExpect(jsonPath("$[?(@.reading.id == " + reading.getId() + ")].changed_since_read").value(false));
        assertTrue(articleReadReceiptRepository.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                article.getId(), 1, operator.getId()).isPresent(), "the first version's receipt is kept");
        assertTrue(articleReadReceiptRepository.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                article.getId(), 2, operator.getId()).isPresent(), "the new version is acknowledged");
    }

    /** Only the text counts: an updated_at moved by a retarget or "verified" is not a change to re-read. */
    @Test
    void anArticleTouchedWithoutANewVersionIsNotChangedSinceRead() throws Exception {
        User operator = createUser("comp-touched@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("შეხებული სტატია", false);
        RequiredReading reading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isOk());

        Article touched = articleRepository.findById(article.getId()).orElseThrow();
        touched.setUpdatedAt(TbilisiTime.now().plusMinutes(1));
        articleRepository.saveAndFlush(touched);

        mockMvc.perform(authed(get("/api/compliance/my-readings"), tokenFor(operator)))
                .andExpect(jsonPath("$[?(@.reading.id == " + reading.getId() + ")].changed_since_read").value(false));
    }

    @Test
    void markReadCreatesReadStatusAndReceiptBridge() throws Exception {
        User operator = createUser("comp-op5@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("დასადასტურებელი", false);
        RequiredReading reading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"))
                .andExpect(jsonPath("$.required_reading_id").value((int) (long) reading.getId()));

        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId())
                .filter(s -> "read".equals(s.getStatus())).isPresent());
        assertTrue(articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(article.getId(), 1, operator.getId())
                .isPresent(), "mark-read of an article must also write the versioned read receipt");
        var audit = auditLogRepository.findAll().stream()
                .filter(a -> "MARK_REQUIRED_READING_READ".equals(a.getAction())
                        && operator.getId().equals(a.getAdminId()))
                .findFirst().orElseThrow();
        var details = objectMapper.readTree(audit.getDetails());
        assertTrue(details.get("before").isNull());
        assertEquals("read", details.at("/after/status").asText());
        assertEquals(reading.getId().longValue(), details.at("/after/required_reading_id").asLong());
        assertEquals("SUCCESS", details.get("result").asText());
    }

    @Test
    void sameRequiredReadingStatusAndReceiptRemainIsolatedPerUser() throws Exception {
        User firstReader = createUser("comp-isolation-first@magti.ge", Role.OPERATOR, "ოფისი");
        User secondReader = createUser("comp-isolation-second@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("ორი მკითხველის მტკიცებულება", false);
        RequiredReading reading = createReading(
                "article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(firstReader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"));

        assertTrue(readStatusRepository
                .findByUserIdAndRequiredReadingId(firstReader.getId(), reading.getId()).isPresent());
        assertTrue(readStatusRepository
                .findByUserIdAndRequiredReadingId(secondReader.getId(), reading.getId()).isEmpty(),
                "one caller's acknowledgement must not create another caller's status");
        assertTrue(articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                        article.getId(), article.getVersion(), firstReader.getId()).isPresent());
        assertTrue(articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                        article.getId(), article.getVersion(), secondReader.getId()).isEmpty());

        String secondReaderBody = mockMvc.perform(
                        authed(get("/api/compliance/my-readings"), tokenFor(secondReader)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var secondReaderRows = objectMapper.readTree(secondReaderBody);
        var secondReaderRow = java.util.stream.StreamSupport.stream(secondReaderRows.spliterator(), false)
                .filter(row -> row.path("reading").path("id").asLong() == reading.getId())
                .findFirst()
                .orElseThrow();
        assertEquals("unread", secondReaderRow.path("status").asText());

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(secondReader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"));

        assertEquals(2, readStatusRepository.findAll().stream()
                .filter(row -> reading.getId().equals(row.getRequiredReadingId()))
                .count());
        assertTrue(articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                        article.getId(), article.getVersion(), secondReader.getId()).isPresent());
    }

    @Test
    void markReadRejectsCrossDepartmentReading() throws Exception {
        User techOperator = createUser("comp-tech@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Article article = createArticle("ოფისის სტატია", false);
        RequiredReading officeReading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + officeReading.getId()), tokenFor(techOperator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("ეს მასალა თქვენს დეპარტამენტს არ ეხება"));
        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(
                techOperator.getId(), officeReading.getId()).isEmpty());
        assertTrue(articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                        article.getId(), article.getVersion(), techOperator.getId()).isEmpty());
    }

    @Test
    void markReadAllowsPrefixMatchOnParentDepartment() throws Exception {
        User techOperator = createUser("comp-tech2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Article article = createArticle("ტექნიკური სტატია", false);
        RequiredReading parentReading = createReading("article", article.getId(), "ტექნიკური", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + parentReading.getId()), tokenFor(techOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"));
    }

    @Test
    void markReadEnforcesQuizGateThenPassesAfterAttempt() throws Exception {
        User operator = createUser("comp-quiz@magti.ge", Role.OPERATOR, "ოფისი");
        Article quizArticle = createArticle("ქვიზიანი სტატია", true);
        RequiredReading reading = createReading("article", quizArticle.getId(), "ოფისი", TbilisiTime.now().plusDays(5));

        // No passing attempt yet -> gate blocks.
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("საჭიროა ქვიზის წარმატებით ჩაბარება წაკითხვის დასადასტურებლად"));

        // Record a passing attempt at the article's current version -> gate opens.
        QuizAttempt attempt = new QuizAttempt();
        attempt.setArticleId(quizArticle.getId());
        attempt.setArticleVersion(1);
        attempt.setUserId(operator.getId());
        attempt.setAttemptNumber(1);
        attempt.setScore(1);
        attempt.setTotalQuestions(1);
        attempt.setPassed(true);
        attempt.setCreatedAt(TbilisiTime.now());
        quizAttemptRepository.saveAndFlush(attempt);

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"));
    }

    @Test
    void creatingRequiredReadingFansOutFixedAssignmentReminders() throws Exception {
        User admin = createUser("comp-fanout-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User op1 = createUser("comp-fanout-op1@magti.ge", Role.OPERATOR, "ოფისი");
        User op2 = createUser("comp-fanout-op2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 02");
        Article article = createArticle("გასაცნობი მასალა", false);

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "All", "2030-09-01T00:00:00+04:00")))
                .andExpect(status().isOk());

        List<Reminder> op1Inbox = reminderRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(op1.getId(), PageRequest.of(0, 1_000)).getContent();
        List<Reminder> op2Inbox = reminderRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(op2.getId(), PageRequest.of(0, 1_000)).getContent();
        List<Reminder> adminInbox = reminderRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(admin.getId(), PageRequest.of(0, 1_000)).getContent();

        assertEquals(1, op1Inbox.size());
        assertEquals(1, op2Inbox.size());
        assertTrue(op1Inbox.get(0).getContentSnapshot().contains("გასაცნობი მასალა"));
        assertTrue(op1Inbox.get(0).getContentSnapshot().contains("2030-09-01"));
        assertTrue(adminInbox.isEmpty(), "the admin who created the reading must not notify themselves");
    }

    /**
     * BL-05: the notifier was the ONE place that did not apply
     * ComplianceCalculator::isEligible. Management roles are excluded from
     * required reading everywhere else -- getMyReadings returns an empty
     * list for them -- yet they were messaged about every new obligation, so
     * a manager got an inbox item telling them to read something that never
     * appears in their list and that they are not measured on.
     */
    @Test
    void managementRolesAreNotNotifiedAboutReadingsTheyWillNeverSee() throws Exception {
        User admin = createUser("bl05-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User manager = createUser("bl05-manager@magti.ge", Role.MANAGER, "ოფისი");
        User otherContentAdmin = createUser("bl05-ca@magti.ge", Role.CONTENT_ADMIN, "ოფისი");
        User operator = createUser("bl05-op@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("მხოლოდ ოპერატორებისთვის", false);

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "ოფისი", "2030-09-01T00:00:00+04:00")))
                .andExpect(status().isOk());

        assertEquals(1, reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                operator.getId(), PageRequest.of(0, 1_000)).getContent().size(),
                "the eligible operator must still be notified");
        assertTrue(reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                manager.getId(), PageRequest.of(0, 1_000)).isEmpty(),
                "a manager is excluded from required reading everywhere else -- notifying them is a message with no matching task");
        assertTrue(reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                otherContentAdmin.getId(), PageRequest.of(0, 1_000)).isEmpty(),
                "same for content admins, who are also a management role here");
    }

    @Test
    void markReadAndUpdateDeleteMissingReadingAre404() throws Exception {
        User admin = createUser("comp-404-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("comp-404-op@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/compliance/mark-read/999999999"), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სავალდებულო მასალა ვერ მოიძებნა"));

        mockMvc.perform(authed(put("/api/compliance/required-readings/999999999"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", 1, "All", "2030-01-01T00:00:00+04:00")))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(delete("/api/compliance/required-readings/999999999"), tokenFor(admin)))
                .andExpect(status().isNotFound());
    }

    /** R5 retains historical assignment evidence but unavailable payload can never gain a new acknowledgement. */
    @Test
    void markReadOnATrashedReadingIs404WithoutDeletingTheAssignment() throws Exception {
        User admin = createUser("bl14-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("bl14-op@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle("წასაშლელი სავალდებულო სტატია", true);
        RequiredReading reading = createReading("article", article.getId(), "All", TbilisiTime.now().plusDays(3));
        article.setStatus("archived");
        articleRepository.saveAndFlush(article);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სავალდებულო მასალა ვერ მოიძებნა"));
        assertTrue(requiredReadingRepository.findById(reading.getId()).isPresent(),
                "the historical assignment must survive while its payload is in trash");
        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId()).isEmpty(),
                "unavailable payload must not gain a new read acknowledgement");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingRequiredReadingWithExistingReadReceiptReturns409NotServerError() throws Exception {
        User admin = createUser("comp-409-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("comp-409-op@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("წაკითხული სავალდებულო მასალა", false);
        RequiredReading reading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        markReadDirect(operator, reading);
        try {
            mockMvc.perform(authed(delete("/api/compliance/required-readings/" + reading.getId()), tokenFor(admin)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").isNotEmpty());
            assertTrue(requiredReadingRepository.findById(reading.getId()).isPresent(),
                    "a refused delete must preserve the assignment after the service transaction rolls back");
            assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId()).isPresent(),
                    "existing read evidence must survive the refused delete");
            assertTrue(auditLogRepository.findAll().stream().noneMatch(row ->
                    "DELETE_REQUIRED_READING".equals(row.getAction()) && reading.getId().equals(row.getItemId())));
        } finally {
            readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId())
                    .ifPresent(readStatusRepository::delete);
            requiredReadingRepository.findById(reading.getId()).ifPresent(requiredReadingRepository::delete);
            articleRepository.deleteById(article.getId());
            userRepository.deleteById(operator.getId());
            userRepository.deleteById(admin.getId());
        }
    }

    /**
     * SEC-06 acceptance for {@code compliance.assign}: it was in the catalog
     * and offered as a switch, but assigning mandatory reading gated on the
     * ROLE alone, so revoking it changed nothing. The admin below keeps
     * CONTENT_ADMIN throughout; only the permission is taken away.
     */
    @Test
    void aContentAdminWithoutComplianceAssignCannotCreateChangeOrDeleteObligations() throws Exception {
        User admin = createUser("sec06-comp@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle("სავალდებულო მასალა", false);
        RequiredReading existing = createReading("article", article.getId(), "All", TbilisiTime.now().plusDays(5));

        UserPermissionOverride deny = new UserPermissionOverride();
        deny.setUserId(admin.getId());
        deny.setPermission(Permission.COMPLIANCE_ASSIGN.value());
        deny.setState(UserPermissionOverride.State.DENY);
        deny.setUpdatedAt(TbilisiTime.now());
        deny.setUpdatedBy(admin.getId());
        permissionOverrideRepository.saveAndFlush(deny);
        long readingsBefore = requiredReadingRepository.count();
        long auditsBefore = auditLogRepository.count();
        entityManager.flush();
        entityManager.clear();
        var dueBefore = requiredReadingRepository.findById(existing.getId()).orElseThrow().getDueDate();

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "All", "2030-01-01T00:00:00+04:00")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/compliance/required-readings/" + existing.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "All", "2030-01-01T00:00:00+04:00")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/compliance/required-readings/" + existing.getId()), tokenFor(admin)))
                .andExpect(status().isForbidden());

        // Phase 6 gives the edit drawer the same capability contract as the
        // create/change/delete actions.
        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isForbidden());
        entityManager.clear();
        assertEquals(readingsBefore, requiredReadingRepository.count());
        assertEquals(dueBefore, requiredReadingRepository.findById(existing.getId()).orElseThrow().getDueDate());
        assertEquals(auditsBefore, auditLogRepository.count());
    }

    /**
     * BL-04: read_statuses is keyed on required_reading_id (V22:11), not on
     * the item, so re-pointing a reading at a different article used to
     * carry every "read" status across. Assign new material that way and
     * the dashboard reports 100% compliance the instant it is saved, for
     * people who have never seen it.
     */
    @Test
    void aRequiredReadingCannotBeRepointedAtADifferentItem() throws Exception {
        User admin = createUser("bl04-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("bl04-op@magti.ge", Role.OPERATOR, "All");
        Article original = createArticle("ძველი მასალა", false);
        Article replacement = createArticle("სრულიად ახალი მასალა", false);
        RequiredReading reading = createReading("article", original.getId(), "All", TbilisiTime.now().plusDays(5));
        markReadDirect(operator, reading);

        mockMvc.perform(authed(put("/api/compliance/required-readings/" + reading.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", replacement.getId(), "All", "2031-01-01T00:00:00+04:00")))
                .andExpect(status().isConflict());

        RequiredReading reloaded = requiredReadingRepository.findById(reading.getId()).orElseThrow();
        assertEquals(original.getId(), reloaded.getItemId(),
                "the rejected re-point must not have been flushed -- the method is @Transactional over a managed entity");

        // The operator's status is still against the material they actually
        // read, which is the whole point.
        assertEquals("read",
                readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId())
                        .orElseThrow().getStatus());
    }

    /** Everything except the item itself is still editable, and now leaves an audit row. */
    @Test
    void changingOnlyTheDeadlineIsAllowedAndAudited() throws Exception {
        User admin = createUser("bl04-audit@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle("ვადის შესაცვლელი", false);
        RequiredReading reading = createReading("article", article.getId(), "All", TbilisiTime.now().plusDays(2));

        mockMvc.perform(authed(put("/api/compliance/required-readings/" + reading.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "ოფისი", "2031-05-05T00:00:00+04:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_department").value("ოფისი"));

        // The deadline decides who counts as overdue; before BL-04 this
        // endpoint wrote no audit row at all, so moving it left no record.
        var audit = auditLogRepository.findAll().stream()
                .filter(a -> "UPDATE_REQUIRED_READING".equals(a.getAction())
                        && reading.getId().equals(a.getItemId())
                        && admin.getId().equals(a.getAdminId()))
                .findFirst().orElseThrow();
        var details = objectMapper.readTree(audit.getDetails());
        assertEquals("All", details.at("/before/target_department").asText());
        assertEquals("ოფისი", details.at("/after/target_department").asText());
        assertEquals("SUCCESS", details.get("result").asText());
    }
}
