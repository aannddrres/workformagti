package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The owner's decisions after QA round 5 (2026-10-03), each pinned where it
 * is decided: PO-54 (unarchive returns an article to its archived-from
 * state), PO-55 (three failed quiz attempts, then ten minutes), PO-56 (a
 * session that ended says so), PO-58 (a deadline at most two years away).
 * The scripts that found them are in scripts/qa/.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QaRound5DecisionsIntegrationTest {

    private static final String DEPT = "ტექნიკური";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    private User user(Role role) {
        User user = new User();
        user.setEmail("r5-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("QA რაუნდი 5");
        user.setRole(role);
        user.setDepartment(DEPT);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String token(User user) {
        return jwtService.createAccessTokenFor(user);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Article article(String status, OffsetDateTime publishedAt, boolean quiz) {
        Article article = new Article();
        article.setTitle("რაუნდი 5 " + System.nanoTime());
        article.setContent("<p>QA</p>");
        article.setStatus(status);
        article.setPublishedAt(publishedAt);
        article.setDraft(false);
        article.setQuizEnabled(quiz);
        article.setVersion(1);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        ArticleTargetDepartment target = new ArticleTargetDepartment();
        target.setArticleId(saved.getId());
        target.setDepartment(DEPT);
        targetDepartmentRepository.saveAndFlush(target);
        return saved;
    }

    private String statusOf(Long id) {
        return articleRepository.findById(id).orElseThrow().getStatus();
    }

    // -- PO-54 ------------------------------------------------------------------

    @Test
    void anArchivedDraftComesBackADraftAndStaysHidden() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        String operator = token(user(Role.OPERATOR));
        Article draft = article("draft", null, false);

        mockMvc.perform(as(post("/api/articles/" + draft.getId() + "/archive"), editor)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/articles/" + draft.getId() + "/unarchive"), editor)).andExpect(status().isOk());

        assertEquals("draft", statusOf(draft.getId()));
        mockMvc.perform(as(get("/api/articles/" + draft.getId()), operator)).andExpect(status().isNotFound());
    }

    @Test
    void anArchivedScheduledArticleComesBackScheduledAndStaysHiddenUntilItsMoment() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        String operator = token(user(Role.OPERATOR));
        Article next = article("scheduled", TbilisiTime.now().plusDays(7), false);

        mockMvc.perform(as(post("/api/articles/" + next.getId() + "/archive"), editor)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/articles/" + next.getId() + "/unarchive"), editor)).andExpect(status().isOk());

        assertEquals("scheduled", statusOf(next.getId()));
        mockMvc.perform(as(get("/api/articles/" + next.getId()), operator)).andExpect(status().isNotFound());
    }

    @Test
    void bulkUnarchiveReturnsEachArticleToItsOwnEarlierState() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        Article draft = article("draft", null, false);
        Article next = article("scheduled", TbilisiTime.now().plusDays(7), false);
        Article live = article("published", TbilisiTime.now().minusDays(1), false);
        String ids = "[" + draft.getId() + "," + next.getId() + "," + live.getId() + "]";

        mockMvc.perform(as(post("/api/articles/bulk-archive"), editor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + ids + ",\"archive\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3));
        mockMvc.perform(as(post("/api/articles/bulk-archive"), editor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + ids + ",\"archive\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3));

        assertEquals("draft", statusOf(draft.getId()));
        assertEquals("scheduled", statusOf(next.getId()));
        assertEquals("published", statusOf(live.getId()));
    }

    // -- PO-55 ------------------------------------------------------------------

    @Test
    void threeFailedQuizAttemptsThenTheFourthWaitsAndIsNotRecorded() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        User operator = user(Role.OPERATOR);
        String op = token(operator);
        Article quiz = article("published", TbilisiTime.now().minusDays(1), true);

        Map<String, Object> body = Map.of("questions", List.of(Map.of(
                "question_text", "სწორია?", "position", 0,
                "answers", List.of(
                        Map.of("answer_text", "კი", "is_correct", true, "position", 0),
                        Map.of("answer_text", "არა", "is_correct", false, "position", 1)))));
        mockMvc.perform(as(put("/api/articles/" + quiz.getId() + "/quiz/admin"), editor)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        JsonNode q = json.readTree(mockMvc.perform(as(get("/api/articles/" + quiz.getId() + "/quiz"), op))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode question = q.get("questions").get(0);
        long wrong = -1;
        for (JsonNode answer : question.get("answers")) {
            if ("არა".equals(answer.get("answer_text").asText())) {
                wrong = answer.get("id").asLong();
            }
        }
        Map<String, Object> wrongAnswer = new LinkedHashMap<>();
        wrongAnswer.put("answers", Map.of(question.get("id").asText(), wrong));
        String attempt = json.writeValueAsString(wrongAnswer);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(as(post("/api/articles/" + quiz.getId() + "/quiz/attempt"), op)
                    .contentType(MediaType.APPLICATION_JSON).content(attempt))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        }
        mockMvc.perform(as(post("/api/articles/" + quiz.getId() + "/quiz/attempt"), op)
                        .contentType(MediaType.APPLICATION_JSON).content(attempt))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("quiz_cooldown"));
        assertEquals(3, quizAttemptRepository.countByArticleIdAndArticleVersionAndUserId(
                quiz.getId(), quiz.getVersion(), operator.getId()));
    }

    // -- PO-56 ------------------------------------------------------------------

    @Test
    void anExpiredSessionSaysSoAndAMadeUpTokenDoesNot() throws Exception {
        User operator = user(Role.OPERATOR);
        String expired = jwtService.createAccessToken(Map.of(
                "sub", operator.getEmail(), "role", operator.getRole().value(), "tv", operator.getTokenVersion()),
                Duration.ofSeconds(-60));
        mockMvc.perform(as(get("/api/users/me"), expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session_expired"));
        mockMvc.perform(as(get("/api/users/me"), "not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // -- PO-58 ------------------------------------------------------------------

    @Test
    void aDeadlineMoreThanTwoYearsAwayIsRefusedAndOneWithinIsNot() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        Article live = article("published", TbilisiTime.now().minusDays(1), false);
        String far = TbilisiTime.now().plusYears(2).plusDays(1).toString();
        String near = TbilisiTime.now().plusYears(1).toString();

        mockMvc.perform(as(post("/api/compliance/required-readings"), editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + live.getId()
                                + ",\"target_department\":\"" + DEPT + "\",\"due_date\":\"" + far + "\",\"priority\":\"high\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(as(post("/api/compliance/required-readings"), editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + live.getId()
                                + ",\"target_department\":\"" + DEPT + "\",\"due_date\":\"" + near + "\",\"priority\":\"high\"}"))
                .andExpect(status().isOk());
    }

    // -- PO-45, found by ZAP in QA round 5 ---------------------------------------

    @Test
    void anUploadWithoutAFileIsAClearRefusalNotAnUnexpectedError() throws Exception {
        String editor = token(user(Role.CONTENT_ADMIN));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/upload")
                        .file(new org.springframework.mock.web.MockMultipartFile("not-file", "x.txt", "text/plain", new byte[] {1}))
                        .header("Authorization", "Bearer " + editor))
                .andExpect(status().isBadRequest());
    }
}
