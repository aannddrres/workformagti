package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Message;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.MessageRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
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
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private VideoInstructionRepository videoInstructionRepository;
    @Autowired
    private ArticleReadReceiptRepository articleReadReceiptRepository;
    @Autowired
    private QuizAttemptRepository quizAttemptRepository;
    @Autowired
    private MessageRepository messageRepository;
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

    private Article createArticle(String title, boolean quizEnabled) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        article.setQuizEnabled(quizEnabled);
        return articleRepository.saveAndFlush(article);
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

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", 1, "All", "2030-01-01T00:00:00+04:00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));
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

    @Test
    void myReadingsResolvesVideoContentAndMissingItemFallback() throws Exception {
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
        assertEquals("Item #999999999", byReadingId.get(ghostReading.getId()).get("item_title").asText());
        assertEquals("Content not available.", byReadingId.get(ghostReading.getId()).get("item_content").asText());
    }

    @Test
    void myProgressComputesPercentage() throws Exception {
        User operator = createUser("comp-op4@magti.ge", Role.OPERATOR, "ოფისი");
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
                .findByArticleIdAndArticleVersionAndOperatorId(article.getId(), 1, operator.getId())
                .isPresent(), "mark-read of an article must also write the versioned read receipt");
    }

    @Test
    void markReadRejectsCrossDepartmentReading() throws Exception {
        User techOperator = createUser("comp-tech@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Article article = createArticle("ოფისის სტატია", false);
        RequiredReading officeReading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + officeReading.getId()), tokenFor(techOperator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("ეს მასალა თქვენს დეპარტამენტს არ ეხება"));
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
    void creatingRequiredReadingFansOutInboxMessages() throws Exception {
        User admin = createUser("comp-fanout-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User op1 = createUser("comp-fanout-op1@magti.ge", Role.OPERATOR, "ოფისი");
        User op2 = createUser("comp-fanout-op2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 02");
        Article article = createArticle("გასაცნობი მასალა", false);

        mockMvc.perform(authed(post("/api/compliance/required-readings"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requiredReadingJson("article", article.getId(), "All", "2030-09-01T00:00:00+04:00")))
                .andExpect(status().isOk());

        List<Message> op1Inbox = messageRepository.findByUserId(op1.getId());
        List<Message> op2Inbox = messageRepository.findByUserId(op2.getId());
        List<Message> adminInbox = messageRepository.findByUserId(admin.getId());

        assertEquals(1, op1Inbox.size());
        assertEquals(1, op2Inbox.size());
        assertTrue(op1Inbox.get(0).getContent().contains("გასაცნობი მასალა"));
        assertTrue(op1Inbox.get(0).getContent().contains("2030-09-01"));
        assertTrue(adminInbox.isEmpty(), "the admin who created the reading must not notify themselves");
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

    /**
     * BL-14: {@code markRead} only consulted the quiz gate {@code if
     * (readingArticle != null)}, so an orphaned required reading (its
     * article gone) let anyone mark it read unconditionally -- quiz or no
     * quiz. The audit noted this "resolves itself once BL-02 is fixed": once
     * {@code ContentDeletionService} deletes a required reading along with
     * its article, {@code markRead}'s OWN lookup at {@code readingId} 404s
     * before the null-article branch is ever reached. This proves that end
     * to end through the real DELETE /api/articles endpoint, not just by
     * reasoning about the code.
     */
    @Test
    void markReadOnAReadingOrphanedByArticleDeletionIs404NotSilentSuccess() throws Exception {
        User admin = createUser("bl14-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("bl14-op@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle("წასაშლელი სავალდებულო სტატია", true);
        RequiredReading reading = createReading("article", article.getId(), "All", TbilisiTime.now().plusDays(3));

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სავალდებულო მასალა ვერ მოიძებნა"));
        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId()).isEmpty(),
                "no read_statuses row should be written for a reading that no longer exists");
    }

    @Test
    void deletingRequiredReadingWithExistingReadReceiptReturns409NotServerError() throws Exception {
        User admin = createUser("comp-409-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("comp-409-op@magti.ge", Role.OPERATOR, "ოფისი");
        Article article = createArticle("წაკითხული სავალდებულო მასალა", false);
        RequiredReading reading = createReading("article", article.getId(), "ოფისი", TbilisiTime.now().plusDays(5));
        markReadDirect(operator, reading);

        mockMvc.perform(authed(delete("/api/compliance/required-readings/" + reading.getId()), tokenFor(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
