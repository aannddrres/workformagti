package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as the other Content-domain integration tests this
 * session.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuizControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

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
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private Article createArticle(boolean quizEnabled, List<String> targetDepartments) {
        Category cat = categoryRepository.saveAndFlush(newCategory());
        Article article = new Article();
        article.setTitle("ტესტ სტატია");
        article.setContent("შინაარსი");
        article.setCategoryId(cat.getId());
        article.setStatus("published");
        article.setDraft(false);
        article.setQuizEnabled(quizEnabled);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        Article saved = articleRepository.saveAndFlush(article);
        for (String dept : targetDepartments) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(saved.getId());
            row.setDepartment(dept);
            targetDepartmentRepository.save(row);
        }
        return saved;
    }

    private Category newCategory() {
        Category category = new Category();
        category.setName("ქვიზ-კატეგორია-" + System.nanoTime());
        category.setActive(true);
        return category;
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static final String TWO_QUESTION_PAYLOAD = """
            {"questions":[
              {"question_text":"რა არის 2+2?","position":0,"answers":[
                {"answer_text":"3","is_correct":false,"position":0},
                {"answer_text":"4","is_correct":true,"position":1}
              ]},
              {"question_text":"რა ფერია ცა?","position":1,"answers":[
                {"answer_text":"ლურჯი","is_correct":true,"position":0},
                {"answer_text":"მწვანე","is_correct":false,"position":1}
              ]}
            ]}""";

    // ── admin quiz CRUD + validation ─────────────────────────────────

    @Test
    void operatorCannotEditQuiz() throws Exception {
        User operator = createUser("qa1@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle(true, List.of("All"));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_QUESTION_PAYLOAD))
                .andExpect(status().isForbidden());
    }

    @Test
    void puttingEmptyQuestionsIs422() throws Exception {
        User admin = createUser("qa2@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[]}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("ქვიზს უნდა ჰქონდეს მინიმუმ ერთი კითხვა"));
    }

    @Test
    void puttingAQuestionWithOneAnswerIs422() throws Exception {
        User admin = createUser("qa3@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"question_text\":\"x\",\"position\":0,"
                                + "\"answers\":[{\"answer_text\":\"a\",\"is_correct\":true,\"position\":0}]}]}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("ყოველ კითხვას უნდა ჰქონდეს მინიმუმ 2 პასუხი"));
    }

    @Test
    void puttingAQuestionWithTwoCorrectAnswersIs422() throws Exception {
        User admin = createUser("qa4@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"question_text\":\"x\",\"position\":0,\"answers\":["
                                + "{\"answer_text\":\"a\",\"is_correct\":true,\"position\":0},"
                                + "{\"answer_text\":\"b\",\"is_correct\":true,\"position\":1}]}]}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("ყოველ კითხვას უნდა ჰქონდეს ზუსტად ერთი სწორი პასუხი"));
    }

    @Test
    void adminReplacesQuizAndAuditsIt() throws Exception {
        User admin = createUser("qa5@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_QUESTION_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].answers[1].is_correct").value(true));

        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(a -> "UPDATE_QUIZ".equals(a.getAction()) && article.getId().equals(a.getItemId())));

        mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[1].question_text").value("რა ფერია ცა?"));

        // Full replace with a single question -- old two are gone, not merged.
        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"question_text\":\"მარტო კითხვა\",\"position\":0,\"answers\":["
                                + "{\"answer_text\":\"კი\",\"is_correct\":true,\"position\":0},"
                                + "{\"answer_text\":\"არა\",\"is_correct\":false,\"position\":1}]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1));
    }

    // ── public quiz view ──────────────────────────────────────────────

    @Test
    void publicQuizNeverExposesIsCorrect() throws Exception {
        User admin = createUser("qa6@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));
        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_QUESTION_PAYLOAD))
                .andExpect(status().isOk());

        User operator = createUser("qa7@magti.ge", Role.OPERATOR, "All");
        String body = mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/quiz"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.article_version").value(1))
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertTrue(!body.contains("is_correct"), "public quiz payload must never mention is_correct");
    }

    @Test
    void quizDisabledArticleReturns404ForPublicQuiz() throws Exception {
        User operator = createUser("qa8@magti.ge", Role.OPERATOR, "All");
        Article article = createArticle(false, List.of("All"));

        mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/quiz"), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ამ სტატიას კვიზი არ აქვს"));
    }

    // ── attempt grading ───────────────────────────────────────────────

    @Test
    void perfectAttemptPasses() throws Exception {
        User admin = createUser("qa9@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));
        String adminBody = mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTION_PAYLOAD))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode questions =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(adminBody).get("questions");
        long q1 = questions.get(0).get("id").asLong();
        long q1CorrectAnswer = questions.get(0).get("answers").get(1).get("id").asLong();
        long q2 = questions.get(1).get("id").asLong();
        long q2CorrectAnswer = questions.get(1).get("answers").get(0).get("id").asLong();

        User operator = createUser("qa10@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/quiz/attempt"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"" + q1 + "\":" + q1CorrectAnswer + ",\"" + q2 + "\":"
                                + q2CorrectAnswer + "}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(2))
                .andExpect(jsonPath("$.total_questions").value(2))
                .andExpect(jsonPath("$.wrong_question_ids.length()").value(0))
                .andExpect(jsonPath("$.attempt_number").value(1));

        // Second attempt (even a wrong one) increments attempt_number.
        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/quiz/attempt"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.wrong_question_ids.length()").value(2))
                .andExpect(jsonPath("$.attempt_number").value(2));
    }

    // ── knowledge score ───────────────────────────────────────────────

    @Test
    void knowledgeScoreAwardsFirstTryBonus() throws Exception {
        User admin = createUser("qa11@magti.ge", Role.CONTENT_ADMIN, "All");
        Article article = createArticle(true, List.of("All"));
        String adminBody = mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/quiz/admin"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON).content(TWO_QUESTION_PAYLOAD))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode questions =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(adminBody).get("questions");
        long q1 = questions.get(0).get("id").asLong();
        long q1CorrectAnswer = questions.get(0).get("answers").get(1).get("id").asLong();
        long q2 = questions.get(1).get("id").asLong();
        long q2CorrectAnswer = questions.get(1).get("answers").get(0).get("id").asLong();
        String correctPayload = "{\"answers\":{\"" + q1 + "\":" + q1CorrectAnswer + ",\"" + q2 + "\":"
                + q2CorrectAnswer + "}}";

        User operator = createUser("qa12@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(get("/api/users/me/knowledge-score"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0));

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/quiz/attempt"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON).content(correctPayload))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/users/me/knowledge-score"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(15))
                .andExpect(jsonPath("$.articles_passed").value(1))
                .andExpect(jsonPath("$.first_try_passes").value(1));
    }

}
