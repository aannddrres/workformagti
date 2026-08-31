package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The bulk tools that make a 122-article import manageable.
 *
 * <p>These exist because the legacy knowledge base arrives all at once and is
 * released gradually. The single-article drawer is the right tool for writing
 * one article and the wrong one for deciding that thirty of them go live on
 * Monday.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleBulkOperationsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user(Role role) {
        User user = new User();
        user.setEmail("bulk-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("მასობრივი ტესტი");
        user.setRole(role);
        user.setDepartment("All");
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

    /** An article in the state the importer leaves behind. */
    private Article importedDraft(String department) {
        Article article = new Article();
        article.setTitle("იმპორტირებული " + System.nanoTime());
        article.setContent("<p>ძველი პორტალიდან</p>");
        article.setStatus("draft");
        article.setDraft(false);
        article.setVersion(1);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        var target = new ge.magti.portal.domain.ArticleTargetDepartment();
        target.setArticleId(saved.getId());
        target.setDepartment(department);
        targetDepartmentRepository.saveAndFlush(target);
        return saved;
    }

    private List<String> departmentsOf(Long articleId) {
        return targetDepartmentRepository.findByArticleId(articleId, PageRequest.of(0, 50))
                .stream().map(t -> t.getDepartment()).toList();
    }

    // ---------------------------------------------------------------- status

    @Test
    void aBatchOfDraftsCanBePublishedInOneCall() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        List<Long> ids = List.of(
                importedDraft("ტექნიკური").getId(),
                importedDraft("ტექნიკური").getId(),
                importedDraft("ოფისი").getId());

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("ids", ids, "status", "published"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3))
                .andExpect(jsonPath("$.skipped_ids").isEmpty());

        for (Long id : ids) {
            Article article = articleRepository.findById(id).orElseThrow();
            assertEquals("published", article.getStatus());
            // Publishing must stamp a date, or the article is published and
            // sorts as though it never was.
            assertTrue(article.getPublishedAt() != null, "published_at must be set");
        }
    }

    /**
     * The state the whole feature turns on. Hiding an article again has to be
     * one call, or "release gradually" means "release and hope".
     */
    @Test
    void publishedArticlesCanBeSentBackToDraft() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Article article = importedDraft("ტექნიკური");
        article.setStatus("published");
        articleRepository.saveAndFlush(article);

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", List.of(article.getId()), "status", "draft"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        assertEquals("draft", articleRepository.findById(article.getId()).orElseThrow().getStatus());
    }

    /**
     * is_draft is the personal-autosave flag, and GET /api/articles hides a
     * row carrying it from everyone except its author -- content
     * administrators included. A bulk publish that left it set would put the
     * article live and invisible, which is the worst of both.
     */
    @Test
    void publishingClearsThePersonalDraftFlagThatWouldHideTheArticle() throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User otherAdmin = user(Role.CONTENT_ADMIN);
        Article article = importedDraft("All");
        article.setDraft(true);
        article.setAuthorId(author.getId());
        articleRepository.saveAndFlush(article);

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", List.of(article.getId()), "status", "published"))))
                .andExpect(status().isOk());

        assertFalse(articleRepository.findById(article.getId()).orElseThrow().isDraft());

        String body = mockMvc.perform(authed(get("/api/articles?limit=200"), tokenFor(otherAdmin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(String.valueOf(article.getId())),
                "an administrator who did not write it must still see it once published");
    }

    @Test
    void anIdThatDoesNotExistIsReportedRatherThanFailingTheWholeBatch() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long real = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", List.of(real, 99_999_999L), "status", "archived"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped_ids[0]").value(99_999_999L));
    }

    @Test
    void anArticleAlreadyInTheRequestedStatusIsSkippedNotRewritten() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("ids", List.of(id), "status", "draft"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped_ids[0]").value(id));
    }

    @Test
    void anOperatorCannotChangeStatusInBulk() throws Exception {
        User operator = user(Role.OPERATOR);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", List.of(id), "status", "published"))))
                .andExpect(status().isForbidden());

        assertEquals("draft", articleRepository.findById(id).orElseThrow().getStatus());
    }

    @Test
    void anUnknownStatusIsRefused() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", List.of(id), "status", "deleted"))))
                // 400, not 422: this is bean validation on the request shape,
                // which GlobalExceptionHandler answers as a bad request. 422 in
                // this codebase means a business rule refused a well-formed
                // request (an invalid quiz, say), which is a different thing.
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- retarget

    @Test
    void aBatchCanBeMovedToAnotherCategoryAndAudienceAtOnce() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Category category = new Category();
        category.setName("ახალი კატეგორია " + System.nanoTime());
        category.setActive(true);
        Category saved = categoryRepository.saveAndFlush(category);

        List<Long> ids = List.of(
                importedDraft("ტექნიკური").getId(),
                importedDraft("ტექნიკური").getId());

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "ids", ids,
                                "category_id", saved.getId(),
                                "target_departments", List.of("ოფისი", "საინფორმაციო")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        for (Long id : ids) {
            assertEquals(saved.getId(), articleRepository.findById(id).orElseThrow().getCategoryId());
            assertEquals(List.of("ოფისი", "საინფორმაციო"), departmentsOf(id).stream().sorted().toList()
                    .isEmpty() ? List.of() : departmentsOf(id).stream().sorted().toList());
        }
    }

    /** Null means "leave alone", so one field can be changed without the other. */
    @Test
    void changingOnlyTheCategoryLeavesTheAudienceUntouched() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Category category = new Category();
        category.setName("მხოლოდ კატეგორია " + System.nanoTime());
        category.setActive(true);
        Category saved = categoryRepository.saveAndFlush(category);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"category_id\":" + saved.getId() + "}"))
                .andExpect(status().isOk());

        assertEquals(saved.getId(), articleRepository.findById(id).orElseThrow().getCategoryId());
        assertEquals(List.of("ტექნიკური"), departmentsOf(id));
    }

    /**
     * An empty audience is not "hidden", it is "addressed to nobody" -- a
     * state the product has no meaning for and which bulk-status already
     * covers properly.
     */
    @Test
    void anEmptyAudienceIsRefusedRatherThanLeavingArticlesAddressedToNobody() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"target_departments\":[]}"))
                .andExpect(status().isBadRequest());

        assertEquals(List.of("ტექნიკური"), departmentsOf(id));
    }

    @Test
    void aRequestThatChangesNothingIsRefused() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownCategoryIsRefusedBeforeAnythingIsWritten() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"category_id\":99999999}"))
                .andExpect(status().isBadRequest());

        assertEquals(List.of("ტექნიკური"), departmentsOf(id));
    }

    /**
     * Retargeting is gated harder than status on purpose: it is the one bulk
     * operation that can put content in front of people it was not written
     * for. An operator holding neither permission must not reach it.
     */
    @Test
    void anOperatorCannotRetargetInBulk() throws Exception {
        User operator = user(Role.OPERATOR);
        Long id = importedDraft("ტექნიკური").getId();

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"target_departments\":[\"ოფისი\"]}"))
                .andExpect(status().isForbidden());

        assertEquals(List.of("ტექნიკური"), departmentsOf(id));
    }

    /** The cap exists so one request cannot become an unbounded write. */
    @Test
    void anOversizedBatchIsRefused() throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList();

        mockMvc.perform(authed(post("/api/articles/bulk-status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("ids", tooMany, "status", "archived"))))
                .andExpect(status().isBadRequest());
    }
}
