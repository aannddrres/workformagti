package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.ArticleReadReceipt;
import ge.magti.portal.domain.ArticleViewLog;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Favorite;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Tag;
import ge.magti.portal.domain.TagMapping;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserNote;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TagMappingRepository;
import ge.magti.portal.repository.TagRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserNoteRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as {@link VideoControllerIntegrationTest}/{@link
 * CategoryControllerIntegrationTest}. Covers core CRUD + lifecycle
 * (list/get/create/update/autosave/delete/archive/unarchive/bulk-archive)
 * and the small standalone endpoints (notes, verify, stale report, related);
 * history/diff/restore, quiz (its own
 * {@link QuizControllerIntegrationTest}), and read-receipts/views are
 * later slices with their own tests.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPermissionOverrideRepository permissionOverrideRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired
    private ArticleHistoryRepository articleHistoryRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private UserNoteRepository userNoteRepository;
    @Autowired
    private ArticleReadReceiptRepository articleReadReceiptRepository;
    @Autowired
    private ArticleViewLogRepository articleViewLogRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private QuizAttemptRepository quizAttemptRepository;
    @Autowired
    private LeadershipAssignmentRepository leadershipAssignmentRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private FavoriteRepository favoriteRepository;
    @Autowired
    private TagMappingRepository tagMappingRepository;
    @Autowired
    private TagRepository tagRepository;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private void deny(User user, Permission permission) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setUserId(user.getId());
        override.setPermission(permission.value());
        override.setState(UserPermissionOverride.State.DENY);
        override.setUpdatedAt(TbilisiTime.now());
        override.setUpdatedBy(user.getId());
        permissionOverrideRepository.saveAndFlush(override);
    }

    private Category createCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private Article createArticle(String title, Long categoryId, String status, boolean isDraft,
            List<String> targetDepartments, OffsetDateTime publishedAt) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი ტესტისთვის");
        article.setCategoryId(categoryId);
        article.setStatus(status);
        article.setDraft(isDraft);
        article.setPublishedAt(publishedAt);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        // authorId deliberately left null: it has a real FK to users, and no
        // fixed id is guaranteed to exist in this schema.
        Article saved = articleRepository.saveAndFlush(article);
        replaceDepartments(saved.getId(), targetDepartments);
        return saved;
    }

    private void replaceDepartments(Long articleId, List<String> departments) {
        for (String department : departments) {
            ge.magti.portal.domain.ArticleTargetDepartment row = new ge.magti.portal.domain.ArticleTargetDepartment();
            row.setArticleId(articleId);
            row.setDepartment(department);
            targetDepartmentRepository.save(row);
        }
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    // ── list visibility ──────────────────────────────────────────────

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/articles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void contentAdminSeesEverythingIncludingDraftsAndUnpublished() throws Exception {
        User admin = createUser("aa1@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        Category cat = createCategory("კატ-1");
        createArticle("გამოქვეყნებული", cat.getId(), "published", false, List.of("All"), null);
        createArticle("არქივირებული", cat.getId(), "archived", false, List.of("All"), null);
        // routers/articles.py:138-143 -- (is_draft==false OR author_id==me):
        // even an admin only sees a DRAFT if they authored it themselves.
        Article ownDraft = createArticle("საკუთარი დრაფტი", cat.getId(), "draft", true, List.of("All"), null);
        ownDraft.setAuthorId(admin.getId());
        articleRepository.saveAndFlush(ownDraft);
        Article othersDraft = createArticle("სხვის დრაფტი", cat.getId(), "draft", true, List.of("All"), null);
        othersDraft.setAuthorId(createUser("aa1-other@magti.ge", Role.CONTENT_ADMIN, "All").getId());
        articleRepository.saveAndFlush(othersDraft);

        // Scoped to this test's own fresh category -- GET /api/articles is
        // org-wide otherwise, and now collides with the 112 real imported
        // articles (also targeting "All") on this shared Oracle instance.
        mockMvc.perform(authed(get("/api/articles").param("category_id", String.valueOf(cat.getId())), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].title").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "გამოქვეყნებული", "არქივირებული", "საკუთარი დრაფტი")));
    }

    @Test
    void operatorSeesOnlyPublishedDeptMatchedNonDraftArticles() throws Exception {
        User operator = createUser("aa2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Category cat = createCategory("კატ-2");
        createArticle("ხილული", cat.getId(), "published", false, List.of("ტექნიკური"), null);
        createArticle("დრაფტი", cat.getId(), "draft", true, List.of("ტექნიკური"), null);
        createArticle("სხვა დეპარტამენტის", cat.getId(), "published", false, List.of("საინფორმაციო"), null);
        createArticle("არქივირებული", cat.getId(), "archived", false, List.of("ტექნიკური"), null);
        createArticle("მომავალი დაგეგმილი", cat.getId(), "scheduled", false, List.of("ტექნიკური"),
                TbilisiTime.now().plusDays(1));
        createArticle("წარსული დაგეგმილი", cat.getId(), "scheduled", false, List.of("ტექნიკური"),
                TbilisiTime.now().minusDays(1));

        // Scoped to this test's own fresh category -- see the same fix in
        // contentAdminSeesEverythingIncludingDraftsAndUnpublished above.
        mockMvc.perform(authed(get("/api/articles").param("category_id", String.valueOf(cat.getId())), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].title").value(
                        org.hamcrest.Matchers.containsInAnyOrder("ხილული", "წარსული დაგეგმილი")));
    }

    @Test
    void listFiltersByQCategoryAndStatus() throws Exception {
        User admin = createUser("aa3@magti.ge", Role.CONTENT_ADMIN, "All");
        Category catA = createCategory("ფილტრ-კატ-ა");
        Category catB = createCategory("ფილტრ-კატ-ბ");
        createArticle("საძიებო სიტყვა თანხვედრა", catA.getId(), "published", false, List.of("All"), null);
        createArticle("სხვა სათაური", catA.getId(), "draft", false, List.of("All"), null);
        createArticle("საძიებო სიტყვა სხვა კატეგორიაში", catB.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(get("/api/articles").param("q", "საძიებო სიტყვა"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(authed(get("/api/articles")
                        .param("q", "საძიებო სიტყვა").param("category_id", String.valueOf(catA.getId())),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("საძიებო სიტყვა თანხვედრა"));

        mockMvc.perform(authed(get("/api/articles").param("status", "draft"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title").value(org.hamcrest.Matchers.hasItem("სხვა სათაური")));
    }

    // ── single-article visibility ────────────────────────────────────

    @Test
    void gettingADraftArticleIs404ForNonAdmin() throws Exception {
        User operator = createUser("aa4@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-3");
        Article draft = createArticle("დაფარული დრაფტი", cat.getId(), "draft", true, List.of("All"), null);

        mockMvc.perform(authed(get("/api/articles/" + draft.getId()), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სტატია ვერ მოიძებნა"));
    }

    @Test
    void gettingAnArticleFromAnUnrelatedDepartmentIs404() throws Exception {
        User operator = createUser("aa5@magti.ge", Role.OPERATOR, "საინფორმაციო");
        Category cat = createCategory("კატ-4");
        Article article = createArticle("სხვა დეპარტამენტი", cat.getId(), "published", false,
                List.of("ტექნიკური"), null);

        mockMvc.perform(authed(get("/api/articles/" + article.getId()), tokenFor(operator)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanSeeADraftArticleById() throws Exception {
        User admin = createUser("aa6@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-5");
        Article draft = createArticle("ადმინის დრაფტი", cat.getId(), "draft", true, List.of("All"), null);

        mockMvc.perform(authed(get("/api/articles/" + draft.getId()), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ადმინის დრაფტი"));
    }

    // ── create ────────────────────────────────────────────────────────

    @Test
    void operatorCannotCreateAnArticle() throws Exception {
        User operator = createUser("aa7@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-6");

        mockMvc.perform(authed(post("/api/articles"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void revokedArticlesEditPermissionBlocksMutatingEndpointsAndPublishIsGatedSeparately() throws Exception {
        // Regression for bug #314: articles.edit/articles.publish used to be
        // stored per-user (settable via PUT /api/users/{id}/permissions) but
        // never actually consulted -- revoking a content_admin's articles.edit
        // did not block PUT /api/articles/{id}, confirmed live. Now enforced.
        User admin = createUser("aa7b@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-6ბ");
        Article existing = createArticle("არსებული სტატია", cat.getId(), "draft", true, List.of("All"), null);

        // Same content_admin, articles.edit revoked (default set minus edit).
        deny(admin, Permission.ARTICLES_EDIT);

        mockMvc.perform(authed(post("/api/articles"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        mockMvc.perform(authed(put("/api/articles/" + existing.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(patch("/api/articles/" + existing.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/articles/" + existing.getId()), tokenFor(admin)))
                .andExpect(status().isForbidden());

        // A second content_admin keeps articles.edit but has articles.publish
        // revoked -- can still save as draft, but cannot set status=published.
        User editorNoPublish = createUser("aa7c@magti.ge", Role.CONTENT_ADMIN, "All");
        deny(editorNoPublish, Permission.ARTICLES_PUBLISH);

        mockMvc.perform(authed(post("/api/articles"), tokenFor(editorNoPublish))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"დრაფტი\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"],\"status\":\"draft\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles"), tokenFor(editorNoPublish))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"გამოქვეყნებადი\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"],\"status\":\"published\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void contentAdminCreatesAnArticleWithHistoryAndAuthorship() throws Exception {
        User admin = createUser("aa8@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        Category cat = createCategory("კატ-7");

        String body = mockMvc.perform(authed(post("/api/articles"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ახალი სტატია\",\"content\":\"სრული ტექსტი\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"ტექნიკური\",\"საინფორმაციო\"],"
                                + "\"status\":\"published\",\"author_id\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ახალი სტატია"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.author_id").value(admin.getId()))
                .andExpect(jsonPath("$.target_departments.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();

        // author_id in the request body is ignored -- always the authenticated admin.
        Article saved = articleRepository.findById(id).orElseThrow();
        assertEquals(admin.getId(), saved.getAuthorId());
        assertTrue(saved.getPublishedAt() != null, "published status without an explicit published_at should be timestamped now");

        List<ArticleHistory> history = articleHistoryRepository.findAll().stream()
                .filter(h -> h.getArticleId().equals(id)).toList();
        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getVersionId());
        assertEquals("ახალი სტატია", history.get(0).getTitle());
    }

    @Test
    void createAppliesLastVerifiedAtAsSent() throws Exception {
        User admin = createUser("aa9@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-8");
        OffsetDateTime verifiedAt = TbilisiTime.now().minusDays(3);

        String body = mockMvc.perform(authed(post("/api/articles"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"category_id\":" + cat.getId()
                                + ",\"target_departments\":[\"All\"],\"last_verified_at\":\"" + verifiedAt + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();

        assertTrue(articleRepository.findById(id).orElseThrow().getLastVerifiedAt() != null);
    }

    // ── update ────────────────────────────────────────────────────────

    @Test
    void updateBumpsVersionArchivesOldContentAndLeavesAuthorAndVerifiedAtUntouched() throws Exception {
        User admin = createUser("aa10@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-9");
        Article article = createArticle("ძველი სათაური", cat.getId(), "published", false, List.of("All"), null);
        Long originalAuthorId = article.getAuthorId();
        article.setLastVerifiedAt(TbilisiTime.now().minusDays(10));
        articleRepository.saveAndFlush(article);
        OffsetDateTime originalVerifiedAt = article.getLastVerifiedAt();

        mockMvc.perform(authed(put("/api/articles/" + article.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ახალი სათაური\",\"content\":\"ახალი ტექსტი\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"საინფორმაციო\"],"
                                + "\"author_id\":999999,\"last_verified_at\":\"" + TbilisiTime.now() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ახალი სათაური"))
                .andExpect(jsonPath("$.version").value(2));

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(originalAuthorId, reloaded.getAuthorId());
        assertEquals(originalVerifiedAt.toInstant(), reloaded.getLastVerifiedAt().toInstant());

        List<ArticleHistory> history = articleHistoryRepository.findAll().stream()
                .filter(h -> h.getArticleId().equals(article.getId())).toList();
        assertEquals(2, history.size(), "one archived-old-version row plus one new-version row");
        assertTrue(history.stream().anyMatch(h -> h.getVersionId() == 1 && "ძველი სათაური".equals(h.getTitle())));
        assertTrue(history.stream().anyMatch(h -> h.getVersionId() == 2 && "ახალი სათაური".equals(h.getTitle())));

        List<String> depts = targetDepartmentRepository.findByArticleId(article.getId()).stream()
                .map(ge.magti.portal.domain.ArticleTargetDepartment::getDepartment).toList();
        assertEquals(List.of("საინფორმაციო"), depts);
    }

    @Test
    void updatingAMissingArticleIs404() throws Exception {
        User admin = createUser("aa11@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(put("/api/articles/999999999"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\",\"category_id\":1,"
                                + "\"target_departments\":[\"All\"]}"))
                .andExpect(status().isNotFound());
    }

    // ── autosave ──────────────────────────────────────────────────────

    @Test
    void autosaveOnlyTouchesFieldsActuallySent() throws Exception {
        User admin = createUser("aa12@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-10");
        Article article = createArticle("თავდაპირველი სათაური", cat.getId(), "draft", true,
                List.of("ტექნიკური"), null);

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"განახლებული სათაური\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("განახლებული სათაური"));

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals("შინაარსი ტესტისთვის", reloaded.getContent(), "content wasn't in the payload -- must stay untouched");
        assertEquals(cat.getId(), reloaded.getCategoryId());
        List<String> depts = targetDepartmentRepository.findByArticleId(article.getId()).stream()
                .map(ge.magti.portal.domain.ArticleTargetDepartment::getDepartment).toList();
        assertEquals(List.of("ტექნიკური"), depts, "target_departments wasn't in the payload -- must stay untouched");
    }

    @Test
    void autosaveWithEmptyTargetDepartmentsLeavesExistingOnesInPlace() throws Exception {
        User admin = createUser("aa13@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-11");
        Article article = createArticle("სათაური", cat.getId(), "draft", true, List.of("ტექნიკური"), null);

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_departments\":[]}"))
                .andExpect(status().isOk());

        List<String> depts = targetDepartmentRepository.findByArticleId(article.getId()).stream()
                .map(ge.magti.portal.domain.ArticleTargetDepartment::getDepartment).toList();
        assertEquals(List.of("ტექნიკური"), depts);
    }

    /**
     * BL-03, the core of it: autosave rewrote published articles with no
     * version bump, so every read receipt and quiz pass recorded against the
     * old text kept counting for the new one. Publishing must go through PUT,
     * which archives and bumps.
     */
    @Test
    void autosaveRefusesToRewriteAPublishedArticle() throws Exception {
        User admin = createUser("aa18@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-16");
        Article article = createArticle("გამოქვეყნებული", cat.getId(), "published", false, List.of("All"), null);
        int versionBefore = article.getVersion();

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"სრულიად ახალი ტექსტი, რომელიც არავის წაუკითხავს\"}"))
                .andExpect(status().isConflict());

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals("შინაარსი ტესტისთვის", reloaded.getContent(),
                "the rejected autosave must not have been flushed -- the method is @Transactional over a managed entity");
        assertEquals(versionBefore, reloaded.getVersion());
    }

    /**
     * The same rewrite, reached by publishing in the same call. Rejected on
     * the PROSPECTIVE state, not just the current one, or the guard would be
     * one request wide.
     */
    @Test
    void autosaveRefusesToPublishADraftItself() throws Exception {
        User admin = createUser("aa19@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-17");
        Article article = createArticle("დრაფტი", cat.getId(), "draft", true, List.of("All"), null);

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_draft\":false,\"status\":\"published\"}"))
                .andExpect(status().isConflict());

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertTrue(reloaded.isDraft(), "the draft must still be a draft");
        assertEquals("draft", reloaded.getStatus());
    }

    /**
     * A scheduled article whose time has not come is not readable yet, so
     * nobody can have read it and autosave stays allowed. Pins that the guard
     * is "could a reader have seen this", not a blunt "is it a draft".
     */
    @Test
    void autosaveStillWorksOnAFutureScheduledArticle() throws Exception {
        User admin = createUser("aa20@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-18");
        Article article = createArticle("დაგეგმილი", cat.getId(), "scheduled", false,
                List.of("All"), TbilisiTime.now().plusDays(3));

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"შესწორებული სათაური\"}"))
                .andExpect(status().isOk());

        assertEquals("შესწორებული სათაური",
                articleRepository.findById(article.getId()).orElseThrow().getTitle());
    }

    /** ...and once its time has passed it is readable, so it is locked like any published article. */
    @Test
    void autosaveRefusesAScheduledArticleWhoseTimeHasPassed() throws Exception {
        User admin = createUser("aa21@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-19");
        Article article = createArticle("გამოქვეყნებული განრიგით", cat.getId(), "scheduled", false,
                List.of("All"), TbilisiTime.now().minusHours(2));

        mockMvc.perform(authed(patch("/api/articles/" + article.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"გვიანი ცვლილება\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * The consequence BL-03 is actually about, asserted end to end: an
     * operator's read receipt must not silently survive a rewrite of the
     * text they acknowledged. PUT bumps the version, so the receipt they
     * hold stops matching the current one.
     */
    @Test
    void rewritingAPublishedArticleThroughPutInvalidatesTheOldReadReceipt() throws Exception {
        User admin = createUser("aa22@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("op22@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-20");
        long articleId = createArticleViaApi(tokenFor(admin), "წასაკითხი", "თავდაპირველი ტექსტი", cat.getId());

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipt/me"), tokenFor(operator)))
                .andExpect(jsonPath("$.has_read").value(true))
                .andExpect(jsonPath("$.article_version").value(1));

        mockMvc.perform(authed(put("/api/articles/" + articleId), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"წასაკითხი\",\"content\":\"სრულიად სხვა ტექსტი\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"All\"],\"status\":\"published\","
                                + "\"is_draft\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipt/me"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_read").value(false));
    }

    // ── delete ────────────────────────────────────────────────────────

    @Test
    void movingAnArchivedArticleToTrashKeepsItsRecoverablePayload() throws Exception {
        User admin = createUser("aa14@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-12");
        Article article = createArticle("წასაშლელი", cat.getId(), "archived", false,
                List.of("All", "ტექნიკური"), null);
        ArticleHistory history = new ArticleHistory();
        history.setArticleId(article.getId());
        history.setTitle(article.getTitle());
        history.setContent(article.getContent());
        history.setUpdatedBy(admin.getId());
        history.setVersionId(1);
        articleHistoryRepository.saveAndFlush(history);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        entityManager.clear();
        assertTrue(articleRepository.findById(article.getId()).isEmpty());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM articles WHERE id = ? AND trashed_at IS NOT NULL", Integer.class,
                article.getId()));
        assertEquals(2, targetDepartmentRepository.findByArticleId(article.getId()).size());
        assertEquals(1, articleHistoryRepository.findByArticleId(article.getId()).size());
    }

    /**
     * Bug found during the PM migration-gap audit (2026-08-11): V24 created
     * {@code fk_user_notes_article} without ON DELETE CASCADE (unlike its
     * siblings above), so deleting an article with an existing personal
     * note 500'd on ORA-02292. Fixed by V30. This is the regression test.
     */
    @Test
    void movingAnArticleToTrashKeepsPersonalNotesForRecovery() throws Exception {
        User admin = createUser("aa15@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("op15@magti.ge", Role.OPERATOR, "ტექნიკური");
        Category cat = createCategory("კატ-13");
        Article article = createArticle("პირადი შენიშვნის სტატია", cat.getId(), "archived", false,
                List.of("All"), null);

        UserNote note = new UserNote();
        note.setUserId(operator.getId());
        note.setArticleId(article.getId());
        note.setContent("ჩემი შენიშვნა");
        userNoteRepository.saveAndFlush(note);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        entityManager.clear();
        assertTrue(articleRepository.findById(article.getId()).isEmpty());
        assertTrue(userNoteRepository.findByUserIdAndArticleId(operator.getId(), article.getId()).isPresent());
    }

    /**
     * BL-02: required_readings/read_statuses address the article
     * polymorphically (item_type/item_id, no FK -- V6's own header says so),
     * so Oracle's cascade cannot reach them. Before ContentDeletionService,
     * these rows survived the delete as permanent ghosts -- an operator
     * would see "Item #&lt;id&gt; / Content not available." in my-readings
     * forever, and the compliance denominator kept counting it.
     */
    @Test
    void movingAnArticleToTrashPreservesRequiredReadingEvidence() throws Exception {
        User admin = createUser("aa16@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("op16@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-14");
        Article article = createArticle("სავალდებულო წასაშლელი", cat.getId(), "archived", false, List.of("All"), null);

        RequiredReading required = new RequiredReading();
        required.setItemType("article");
        required.setItemId(article.getId());
        required.setItemTitleSnapshot(article.getTitle());
        required.setTargetDepartment("All");
        required.setDueDate(TbilisiTime.now().plusDays(7));
        RequiredReading savedRequired = requiredReadingRepository.saveAndFlush(required);

        ReadStatus stat = new ReadStatus();
        stat.setUserId(operator.getId());
        stat.setRequiredReadingId(savedRequired.getId());
        stat.setStatus("read");
        stat.setReadAt(TbilisiTime.now());
        readStatusRepository.saveAndFlush(stat);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        assertEquals(1, requiredReadingRepository.findByItemTypeAndItemId("article", article.getId()).size(),
                "required reading is compliance evidence and must survive trash");
        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), savedRequired.getId()).isPresent(),
                "read status is compliance evidence and must survive trash");
    }

    /**
     * BL-10: tags_mapping and favorites are the same shape of polymorphic
     * reference as required_readings, cleared by the same
     * ContentDeletionService call.
     */
    @Test
    void movingAnArticleToTrashKeepsTagsAndFavoritesForRecovery() throws Exception {
        User admin = createUser("aa17@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("op17@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-15");
        Article article = createArticle("ტეგებიანი წასაშლელი", cat.getId(), "archived", false, List.of("All"), null);

        Tag tag = tagRepository.findByName("რეგრესია").orElseGet(() -> {
            Tag created = new Tag();
            created.setName("რეგრესია");
            return tagRepository.saveAndFlush(created);
        });
        TagMapping mapping = new TagMapping();
        mapping.setTagId(tag.getId());
        mapping.setItemType("article");
        mapping.setItemId(article.getId());
        tagMappingRepository.saveAndFlush(mapping);

        Favorite favorite = new Favorite();
        favorite.setUserId(operator.getId());
        favorite.setItemType("article");
        favorite.setItemId(article.getId());
        favoriteRepository.saveAndFlush(favorite);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        assertTrue(tagMappingRepository.findById(mapping.getId()).isPresent());
        assertTrue(favoriteRepository.findByUserIdAndItemTypeAndItemId(operator.getId(), "article", article.getId()).isPresent());
    }

    // ── archive / unarchive / bulk-archive ───────────────────────────

    @Test
    void archivingWritesAnAuditRowAndIsIdempotent() throws Exception {
        User admin = createUser("aa15@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-13");
        Article article = createArticle("დასაარქივებელი", cat.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));

        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(a -> "ARCHIVE".equals(a.getAction()) && "article".equals(a.getItemType())
                        && article.getId().equals(a.getItemId())));

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void unarchivingANonArchivedArticleIsRejected() throws Exception {
        User admin = createUser("aa16@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-14");
        Article article = createArticle("არაარქივი", cat.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/unarchive"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("სტატია არ არის არქივში"));
    }

    @Test
    void operatorCannotArchiveEvenThoughTheyCanView() throws Exception {
        User operator = createUser("aa17@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-15");
        Article article = createArticle("სტატია", cat.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/archive"), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void bulkArchiveUpdatesFoundArticlesAndSkipsTheRest() throws Exception {
        User admin = createUser("aa18@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-16");
        Article toArchive = createArticle("დასაარქივებელი1", cat.getId(), "published", false, List.of("All"), null);
        Article alreadyArchived = createArticle("უკვე არქივში", cat.getId(), "archived", false, List.of("All"), null);
        long missingId = 999999999L;

        mockMvc.perform(authed(post("/api/articles/bulk-archive"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + toArchive.getId() + "," + alreadyArchived.getId() + "," + missingId
                                + "],\"archive\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.status").value("archived"))
                .andExpect(jsonPath("$.skipped_ids.length()").value(2))
                .andExpect(jsonPath("$.skipped_ids").value(
                        org.hamcrest.Matchers.containsInAnyOrder((int) alreadyArchived.getId().longValue(), (int) missingId)));

        assertEquals("archived", articleRepository.findById(toArchive.getId()).orElseThrow().getStatus());

        boolean hasSnapshot = auditLogRepository.findAll().stream()
                .anyMatch(a -> "ARCHIVE".equals(a.getAction()) && toArchive.getId().equals(a.getItemId())
                        && a.getAdminNameSnapshot() != null && a.getItemNameSnapshot() != null);
        assertTrue(hasSnapshot, "bulk-archive sets audit snapshots explicitly, unlike the single-item endpoint");
    }

    @Test
    void bulkArchiveRequiresArticlesArchivePermission() throws Exception {
        User operator = createUser("aa19@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/articles/bulk-archive"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"archive\":true}"))
                .andExpect(status().isForbidden());
    }

    private Team createTeam(String name) {
        Team team = new Team();
        team.setName(name);
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private LeadershipAssignment leadTeam(User leader, Team team, AssignmentType type) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(leader.getId());
        assignment.setTeamId(team.getId());
        assignment.setAssignmentType(type);
        assignment.setActive(true);
        assignment.setStartedAt(TbilisiTime.now());
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        return leadershipAssignmentRepository.saveAndFlush(assignment);
    }

    @Test
    void removedFeedbackEndpointsAreNotExposed() throws Exception {
        User operator = createUser("aa20@magti.ge", Role.OPERATOR, "All");
        User contentAdmin = createUser("aa21@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(post("/api/articles/1/feedback"), tokenFor(operator)))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(get("/api/admin/feedback"), tokenFor(contentAdmin)))
                .andExpect(status().isNotFound());
    }

    // ── notes ─────────────────────────────────────────────────────────

    @Test
    void noteRoundTripsFromMissingToCreatedToUpdated() throws Exception {
        User operator = createUser("aa22@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-17");
        Article article = createArticle("სტატია შენიშვნისთვის", cat.getId(), "published", false, List.of("All"), null);

        String initialBody = mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/note"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("null", initialBody, "no note yet -- must be the literal JSON null, not an empty body");

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/note"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"პირველი ვერსია\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("პირველი ვერსია"))
                .andExpect(jsonPath("$.user_id").value(operator.getId()))
                .andExpect(jsonPath("$.article_id").value(article.getId()));

        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/note"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"განახლებული ვერსია\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("განახლებული ვერსია"));

        assertEquals(1, userNoteRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(operator.getId()) && n.getArticleId().equals(article.getId()))
                .count(), "second PUT must update the existing note, not create a second one");

        mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/note"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("განახლებული ვერსია"));
    }

    @Test
    void noteOnAnInvisibleArticleIs404() throws Exception {
        User operator = createUser("aa23@magti.ge", Role.OPERATOR, "საინფორმაციო");
        Category cat = createCategory("კატ-18");
        Article article = createArticle("სხვა დეპარტამენტის სტატია", cat.getId(), "published", false,
                List.of("ტექნიკური"), null);

        mockMvc.perform(authed(get("/api/articles/" + article.getId() + "/note"), tokenFor(operator)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/articles/" + article.getId() + "/note"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    // ── verify ────────────────────────────────────────────────────────

    @Test
    void verifyingAnArticleBumpsLastVerifiedAtAndAudits() throws Exception {
        User admin = createUser("aa24@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-19");
        Article article = createArticle("დასადასტურებელი", cat.getId(), "published", false, List.of("All"), null);
        OffsetDateTime before = TbilisiTime.now();

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/verify"), tokenFor(admin)))
                .andExpect(status().isOk());

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertTrue(!reloaded.getLastVerifiedAt().isBefore(before));
        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(a -> "VERIFY".equals(a.getAction()) && article.getId().equals(a.getItemId())));
    }

    @Test
    void operatorCannotVerify() throws Exception {
        User operator = createUser("aa25@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-20");
        Article article = createArticle("სტატია", cat.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/verify"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    // ── stale report ──────────────────────────────────────────────────

    @Test
    void staleReportListsOnlyOldPublishedArticles() throws Exception {
        User admin = createUser("aa26@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-21");

        Article stalePublished = createArticle("ძველი გამოქვეყნებული", cat.getId(), "published", false, List.of("All"), null);
        stalePublished.setLastVerifiedAt(TbilisiTime.now().minusDays(200));
        articleRepository.saveAndFlush(stalePublished);

        Article freshPublished = createArticle("ახალი გამოქვეყნებული", cat.getId(), "published", false, List.of("All"), null);
        freshPublished.setLastVerifiedAt(TbilisiTime.now().minusDays(5));
        articleRepository.saveAndFlush(freshPublished);

        Article staleDraft = createArticle("ძველი დრაფტი", cat.getId(), "draft", true, List.of("All"), null);
        staleDraft.setLastVerifiedAt(TbilisiTime.now().minusDays(200));
        articleRepository.saveAndFlush(staleDraft);

        String body = mockMvc.perform(authed(get("/api/admin/articles/stale"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode entries = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);

        List<String> titles = new java.util.ArrayList<>();
        entries.forEach(e -> titles.add(e.get("title").asText()));
        assertTrue(titles.contains("ძველი გამოქვეყნებული"));
        assertTrue(!titles.contains("ახალი გამოქვეყნებული"), "recently-verified articles aren't stale");
        assertTrue(!titles.contains("ძველი დრაფტი"), "only status==published is considered");

        com.fasterxml.jackson.databind.JsonNode staleEntry = java.util.stream.StreamSupport
                .stream(entries.spliterator(), false)
                .filter(e -> "ძველი გამოქვეყნებული".equals(e.get("title").asText()))
                .findFirst().orElseThrow();
        assertTrue(staleEntry.get("days_stale").asLong() >= 200);
    }

    // ── related articles ──────────────────────────────────────────────

    @Test
    void relatedArticlesReturnsEmptyListForAMissingSourceNotA404() throws Exception {
        User operator = createUser("aa27@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(get("/api/articles/999999999/related"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void relatedArticlesPrefersSameCategory() throws Exception {
        User admin = createUser("aa28@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-22");
        Category otherCat = createCategory("კატ-23");
        Article source = createArticle("წყარო", cat.getId(), "published", false, List.of("All"), null);
        Article sameCat1 = createArticle("იგივე კატეგორია 1", cat.getId(), "published", false, List.of("All"), null);
        Article sameCat2 = createArticle("იგივე კატეგორია 2", cat.getId(), "published", false, List.of("All"), null);
        createArticle("სხვა კატეგორია", otherCat.getId(), "published", false, List.of("All"), null);

        mockMvc.perform(authed(get("/api/articles/" + source.getId() + "/related"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title").value(org.hamcrest.Matchers.hasItems(
                        "იგივე კატეგორია 1", "იგივე კატეგორია 2")));
    }

    @Test
    void relatedArticlesDeptFilterIsExactMatchOnlyNotPrefixAware() throws Exception {
        // Deliberately different from every other visibility check in this
        // file: get_related_articles doesn't use the prefix-aware rule, so
        // a sub-group operator must NOT see a parent-department match here.
        User operator = createUser("aa29@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        Category cat = createCategory("კატ-24");
        Article source = createArticle("წყარო2", cat.getId(), "published", false, List.of("ტექნიკური — ჯგუფი 01"), null);
        createArticle("მშობელი დეპარტამენტის სტატია", cat.getId(), "published", false, List.of("ტექნიკური"), null);

        mockMvc.perform(authed(get("/api/articles/" + source.getId() + "/related"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("მშობელი დეპარტამენტის სტატია"))));
    }

    // ── history / diff / restore / versions ──────────────────────────

    private long createArticleViaApi(String token, String title, String content, Long categoryId) throws Exception {
        // is_draft must be set explicitly: ArticleRequest defaults it to
        // true (matching Python's own ArticleBase schema default) when
        // absent, regardless of status -- the two fields are independent.
        // Omitting this made every article this helper creates a draft,
        // which EligibleOperatorsService.forArticle correctly treats as
        // "nobody is eligible to read this yet" (found via a failing test,
        // not assumed).
        String body = mockMvc.perform(authed(post("/api/articles"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"category_id\":"
                                + categoryId + ",\"target_departments\":[\"All\"],\"status\":\"published\","
                                + "\"is_draft\":false}"))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    private void updateArticleViaApi(String token, long articleId, String title, String content, Long categoryId) throws Exception {
        mockMvc.perform(authed(put("/api/articles/" + articleId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"category_id\":"
                                + categoryId + ",\"target_departments\":[\"All\"],\"status\":\"published\"}"))
                .andExpect(status().isOk());
    }

    private long historyIdForVersion(long articleId, int versionId) {
        return articleHistoryRepository.findByArticleId(articleId).stream()
                .filter(h -> h.getVersionId() != null && h.getVersionId() == versionId)
                .findFirst().orElseThrow().getId();
    }

    @Test
    void historyListsRevisionsOrderedByUpdatedAtDescWithAuthorNames() throws Exception {
        User admin = createUser("aa30@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-25");
        long articleId = createArticleViaApi(tokenFor(admin), "ვერსია 1", "შინაარსი 1", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "ვერსია 2", "შინაარსი 2", cat.getId());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("ვერსია 2"))
                .andExpect(jsonPath("$[0].author_name").value(admin.getName()))
                .andExpect(jsonPath("$[1].title").value("ვერსია 1"));
    }

    @Test
    void historyDoesNotRequireTheArticleToExist() throws Exception {
        User admin = createUser("aa31@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(get("/api/articles/999999999/history"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void diffDefaultsToComparingAgainstCurrentContent() throws Exception {
        User admin = createUser("aa32@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-26");
        long articleId = createArticleViaApi(tokenFor(admin), "თავდაპირველი", "პირველი შინაარსი", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "განახლებული", "მეორე შინაარსი", cat.getId());
        long v1HistoryId = historyIdForVersion(articleId, 1);

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history/" + v1HistoryId + "/diff"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_version").value(1))
                .andExpect(jsonPath("$.compare_version").value(2))
                .andExpect(jsonPath("$.version_id").value(1))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("diff-")));
    }

    @Test
    void diffComparesToPredecessorWhenRequested() throws Exception {
        User admin = createUser("aa33@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-27");
        long articleId = createArticleViaApi(tokenFor(admin), "v1 სათაური", "v1 შინაარსი", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "v2 სათაური", "v2 შინაარსი", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "v3 სათაური", "v3 შინაარსი", cat.getId());
        long v2HistoryId = historyIdForVersion(articleId, 2);

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history/" + v2HistoryId + "/diff")
                        .param("compare_to_predecessor", "true"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_version").value(1))
                .andExpect(jsonPath("$.compare_version").value(2));
    }

    @Test
    void diffWithNoPredecessorSelfComparesCleanly() throws Exception {
        User admin = createUser("aa34@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-28");
        long articleId = createArticleViaApi(tokenFor(admin), "მხოლოდ ვერსია", "შინაარსი", cat.getId());
        long v1HistoryId = historyIdForVersion(articleId, 1);

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history/" + v1HistoryId + "/diff")
                        .param("compare_to_predecessor", "true"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_version").value(1))
                .andExpect(jsonPath("$.compare_version").value(1))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.removed").value(0));
    }

    @Test
    void diffMissingHistorySnapshotIs404() throws Exception {
        User admin = createUser("aa35@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-29");
        long articleId = createArticleViaApi(tokenFor(admin), "სათაური", "შინაარსი", cat.getId());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history/999999999/diff"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ისტორიის ვერსია ვერ მოიძებნა"));
    }

    @Test
    void restoreBringsBackOldContentAndBumpsVersion() throws Exception {
        User admin = createUser("aa36@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-30");
        long articleId = createArticleViaApi(tokenFor(admin), "ორიგინალი სათაური", "ორიგინალი შინაარსი", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "შეცვლილი სათაური", "შეცვლილი შინაარსი", cat.getId());
        long v1HistoryId = historyIdForVersion(articleId, 1);

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/history/" + v1HistoryId + "/restore"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ორიგინალი სათაური"))
                .andExpect(jsonPath("$.version").value(3));

        Article reloaded = articleRepository.findById(articleId).orElseThrow();
        assertEquals("ორიგინალი სათაური", reloaded.getTitle());
        assertEquals(3, reloaded.getVersion());
        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(a -> "RESTORE".equals(a.getAction()) && Long.valueOf(articleId).equals(a.getItemId())));

        List<ArticleHistory> allHistory = articleHistoryRepository.findByArticleId(articleId);
        assertEquals(3, allHistory.size(), "v1, v2 (from the earlier update), and the new v3 restore snapshot");
        assertTrue(allHistory.stream().anyMatch(h -> h.getVersionId() == 3 && "ორიგინალი სათაური".equals(h.getTitle())));
    }

    @Test
    void operatorCannotSeeAdminOnlyHistoryOrRestore() throws Exception {
        User admin = createUser("aa37@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("aa38@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-31");
        long articleId = createArticleViaApi(tokenFor(admin), "სათაური", "შინაარსი", cat.getId());
        long v1HistoryId = historyIdForVersion(articleId, 1);

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/history"), tokenFor(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/history/" + v1HistoryId + "/restore"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void versionsListsAllRevisionsDescByVersionWithSelfHealing() throws Exception {
        User admin = createUser("aa39@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-32");
        long articleId = createArticleViaApi(tokenFor(admin), "ვერსია ა", "შინაარსი ა", cat.getId());
        updateArticleViaApi(tokenFor(admin), articleId, "ვერსია ბ", "შინაარსი ბ", cat.getId());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/versions"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].title").value("ვერსია ბ"))
                .andExpect(jsonPath("$[1].version").value(1));

        // Self-healing: delete the current version's history row directly,
        // then confirm GET /versions recreates it rather than omitting it.
        ArticleHistory v2Row = articleHistoryRepository.findByArticleId(articleId).stream()
                .filter(h -> h.getVersionId() != null && h.getVersionId() == 2).findFirst().orElseThrow();
        articleHistoryRepository.delete(v2Row);
        articleHistoryRepository.flush();

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/versions"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void longGeorgianContentCanBeUpdatedListedAndRestoredThroughRealOracle() throws Exception {
        User admin = createUser("aa39-clob@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-CLOB");
        String original = "ეს არის გრძელი ქართული სტატიის საწყისი აბზაცი. ".repeat(140);
        String updated = "ეს არის განახლებული ქართული სტატიის სრული აბზაცი. ".repeat(140);
        assertTrue(original.length() > 5_000);
        assertTrue(updated.length() > 5_000);

        long articleId = createArticleViaApi(tokenFor(admin), "CLOB საწყისი ვერსია", original, cat.getId());

        // Save/update path: archives the >5,000-character current version.
        updateArticleViaApi(tokenFor(admin), articleId, "CLOB განახლებული ვერსია", updated, cat.getId());
        ArticleHistory v1 = articleHistoryRepository.findByArticleId(articleId).stream()
                .filter(h -> Integer.valueOf(1).equals(h.getVersionId()))
                .findFirst().orElseThrow();
        assertEquals(original, v1.getContent());

        // History-opening path: self-heals the long current version as CLOB.
        mockMvc.perform(authed(get("/api/articles/" + articleId + "/versions"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2));

        // Restore path: archives the long current version before restoring v1.
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/history/" + v1.getId() + "/restore"),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(original))
                .andExpect(jsonPath("$.version").value(3));

        Article restored = articleRepository.findById(articleId).orElseThrow();
        assertEquals(original, restored.getContent());
        assertTrue(articleHistoryRepository.findByArticleId(articleId).stream()
                .anyMatch(h -> Integer.valueOf(2).equals(h.getVersionId()) && updated.equals(h.getContent())));
    }

    @Test
    void jdbcAndJpaHistoryWritersNormalizeTheSameTbilisiInstantAcrossSessionTimeZones() {
        String originalSessionTimeZone = jdbcTemplate.queryForObject(
                "SELECT SESSIONTIMEZONE FROM dual", String.class);
        assertTrue(originalSessionTimeZone != null
                        && originalSessionTimeZone.matches("[A-Za-z0-9_./:+-]+"),
                "unexpected Oracle session time-zone value: " + originalSessionTimeZone);
        // Exercise both real writer paths through a session zone that differs
        // from CI's UTC JVM and the value's +04 offset.
        jdbcTemplate.execute("ALTER SESSION SET TIME_ZONE = '+09:00'");
        try {
            User admin = createUser("aa39-history-timezone@magti.ge", Role.CONTENT_ADMIN, "All");
            Category category = createCategory("კატ-history-timezone");

            // Legacy rows can have no updated_at; the controller then supplies
            // TbilisiTime.now() to archiveIfMissing.
            Article legacy = new Article();
            legacy.setTitle("Legacy timestamp");
            legacy.setContent("საწყისი შინაარსი");
            legacy.setCategoryId(category.getId());
            legacy.setAuthorId(admin.getId());
            legacy.setUpdatedAt(null);
            legacy = articleRepository.saveAndFlush(legacy);

            OffsetDateTime fallbackTime = TbilisiTime.now().withNano(0);
            articleHistoryRepository.archiveIfMissing(
                    legacy.getId(), legacy.getTitle(), legacy.getContent(), admin.getId(), 1, fallbackTime);

            ArticleHistory jpaWritten = new ArticleHistory();
            jpaWritten.setArticleId(legacy.getId());
            jpaWritten.setTitle("JPA timestamp");
            jpaWritten.setContent("განახლებული შინაარსი");
            jpaWritten.setUpdatedBy(admin.getId());
            jpaWritten.setVersionId(2);
            jpaWritten.setUpdatedAt(fallbackTime);
            articleHistoryRepository.saveAndFlush(jpaWritten);
            entityManager.clear();

            Map<Integer, ArticleHistory> byVersion = articleHistoryRepository.findByArticleId(legacy.getId()).stream()
                    .collect(java.util.stream.Collectors.toMap(ArticleHistory::getVersionId, history -> history));
            assertEquals(fallbackTime, byVersion.get(1).getUpdatedAt());
            assertEquals(fallbackTime, byVersion.get(2).getUpdatedAt());
        } finally {
            entityManager.clear();
            jdbcTemplate.execute("ALTER SESSION SET TIME_ZONE = '" + originalSessionTimeZone + "'");
        }
    }

    // ── read-receipts / views ─────────────────────────────────────────

    private long createArticleViaApiWithDept(
            String token, String title, String content, Long categoryId, String targetDept) throws Exception {
        String body = mockMvc.perform(authed(post("/api/articles"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"category_id\":"
                                + categoryId + ",\"target_departments\":[\"" + targetDept + "\"],\"status\":\"published\","
                                + "\"is_draft\":false}"))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    @Test
    void readReceiptsShowsEligibleOperatorsAndOrphanedSnapshots() throws Exception {
        User admin = createUser("aa40@magti.ge", Role.SYSTEM_ADMIN, "All");
        Category cat = createCategory("კატ-33");
        long articleId = createArticleViaApi(tokenFor(admin), "წასაკითხი სტატია", "შინაარსი", cat.getId());

        User reader = createUser("aa41@magti.ge", Role.OPERATOR, "All");
        User nonReader = createUser("aa42@magti.ge", Role.OPERATOR, "All");
        User formerReader = createUser("aa43@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(formerReader)))
                .andExpect(status().isOk());

        // formerReader is no longer eligible (deactivated), but their
        // receipt must still show as a detached snapshot row.
        formerReader.setActive(false);
        userRepository.saveAndFlush(formerReader);

        String body = mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipts"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode receipts =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("receipts");
        Map<Long, com.fasterxml.jackson.databind.JsonNode> byId = new java.util.HashMap<>();
        receipts.forEach(r -> {
            assertFalse(r.has("operator_email"), "official evidence must not expose employee email");
            byId.put(r.get("operator_id").asLong(), r);
        });

        assertTrue(byId.get(reader.getId()).get("has_read").asBoolean());
        assertFalse(byId.get(nonReader.getId()).get("has_read").asBoolean());
        assertTrue(byId.get(formerReader.getId()).get("has_read").asBoolean(),
                "an orphaned/detached snapshot row must still show as read");
    }

    @Test
    void readReceiptsMarksLateReadsPastTheDueDate() throws Exception {
        User admin = createUser("aa44@magti.ge", Role.SYSTEM_ADMIN, "All");
        Category cat = createCategory("კატ-34");
        long articleId = createArticleViaApi(tokenFor(admin), "ვადაგადაცილებული", "შინაარსი", cat.getId());

        RequiredReading required = new RequiredReading();
        required.setItemType("article");
        required.setItemId(articleId);
        required.setTargetDepartment("All");
        required.setDueDate(TbilisiTime.now().minusDays(1));
        requiredReadingRepository.saveAndFlush(required);

        User lateReader = createUser("aa45@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(lateReader)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipts"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode receipts =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("receipts");
        com.fasterxml.jackson.databind.JsonNode row = java.util.stream.StreamSupport.stream(receipts.spliterator(), false)
                .filter(r -> lateReader.getName().equals(r.get("operator_name").asText()))
                .findFirst().orElseThrow();

        assertTrue(row.get("is_late").asBoolean());
        assertEquals("late_read", row.get("status").asText());
        assertTrue(row.get("read_at").asText().matches("\\d{2}\\\\\\d{2}\\\\\\d{4} \\d{2}:\\d{2}"),
                "read_at must use TbilisiTime.format's literal-backslash date separators: " + row.get("read_at").asText());
    }

    @Test
    void readReceiptRequiresPassingQuizFirst() throws Exception {
        User admin = createUser("aa46@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-35");
        long articleId = createArticleViaApi(tokenFor(admin), "ქვიზიანი", "შინაარსი", cat.getId());
        Article article = articleRepository.findById(articleId).orElseThrow();
        article.setQuizEnabled(true);
        articleRepository.saveAndFlush(article);

        User operator = createUser("aa47@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("საჭიროა ქვიზის წარმატებით ჩაბარება წაკითხვის დასადასტურებლად"));

        QuizAttempt passed = new QuizAttempt();
        passed.setArticleId(articleId);
        passed.setArticleVersion(article.getVersion());
        passed.setUserId(operator.getId());
        passed.setAttemptNumber(1);
        passed.setScore(1);
        passed.setTotalQuestions(1);
        passed.setPassed(true);
        passed.setCreatedAt(TbilisiTime.now());
        quizAttemptRepository.saveAndFlush(passed);

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isOk());
    }

    @Test
    void readReceiptBridgesToRequiredReadingComplianceStatus() throws Exception {
        User admin = createUser("aa48@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-36");
        long articleId = createArticleViaApi(tokenFor(admin), "სავალდებულო წაკითხვა", "შინაარსი", cat.getId());

        RequiredReading required = new RequiredReading();
        required.setItemType("article");
        required.setItemId(articleId);
        required.setTargetDepartment("All");
        required.setDueDate(TbilisiTime.now().plusDays(7));
        RequiredReading savedRequired = requiredReadingRepository.saveAndFlush(required);

        User operator = createUser("aa49@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isOk());

        ReadStatus stat = readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), savedRequired.getId())
                .orElseThrow();
        assertEquals("read", stat.getStatus());
        assertTrue(stat.getReadAt() != null);
    }

    @Test
    void myReadReceiptStatusReflectsWhetherIveRead() throws Exception {
        User admin = createUser("aa50@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("aa51@magti.ge", Role.OPERATOR, "All");
        Category cat = createCategory("კატ-38");
        long articleId = createArticleViaApi(tokenFor(admin), "სტატია", "შინაარსი", cat.getId());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipt/me"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_read").value(false));

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipt/me"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_read").value(true))
                .andExpect(jsonPath("$.article_version").value(1));
    }

    @Test
    void viewTrackingRejectsAnArticleTheCallerCannotSee() throws Exception {
        User admin = createUser("aa52@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-39");
        long articleId = createArticleViaApiWithDept(
                tokenFor(admin), "ტექნიკურის სტატია", "შინაარსი", cat.getId(), "ტექნიკური");
        User unrelatedOperator = createUser("aa53@magti.ge", Role.OPERATOR, "საინფორმაციო");

        // Confirm the operator genuinely can't see it via the normal read path...
        mockMvc.perform(authed(get("/api/articles/" + articleId), tokenFor(unrelatedOperator)))
                .andExpect(status().isNotFound());

        // Logging an inaccessible article would manufacture a false "open"
        // record for content the caller could not actually see.
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(unrelatedOperator)))
                .andExpect(status().isNotFound());

        assertEquals(0, articleViewLogRepository.findByArticleIdSnapshotOrderByViewedAtDesc(articleId).size());
    }

    /**
     * BL-12. Both tables use ON DELETE SET NULL for article_id so that a
     * receipt outlives the article it is about -- but every read path
     * filtered on that same column, so deleting the article left the rows
     * retained and simultaneously unfindable. article_id_snapshot carries
     * the id with no foreign key, so it survives the delete.
     *
     * <p>Asserts both halves at once: article_id IS nulled (the FK still
     * tells you the article is gone, which is information worth keeping)
     * while the row is still addressable by the article it belonged to.
     */
    @Test
    void purgedArticlesReadReceiptsAndViewLogsStayFindable() throws Exception {
        User admin = createUser("aa90@magti.ge", Role.SYSTEM_ADMIN, "All");
        Category cat = createCategory("კატ-88");
        long articleId = createArticleViaApi(tokenFor(admin), "წასაშლელი სტატია", "შინაარსი", cat.getId());
        User operator = createUser("aa91@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(operator)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(operator)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(delete("/api/articles/" + articleId), tokenFor(admin)))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE articles SET purge_after = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(TbilisiTime.now().minusMinutes(1).toLocalDateTime()), articleId);
        mockMvc.perform(authed(delete("/api/content-trash/article/" + articleId), tokenFor(admin)))
                .andExpect(status().isOk());
        // ON DELETE SET NULL happens in the database, so the loaded entities
        // in this transaction's persistence context still hold the old
        // article_id. flush + clear forces the assertions below to read what
        // Oracle actually stored rather than what Hibernate remembers.
        entityManager.flush();
        entityManager.clear();

        List<ArticleViewLog> views = articleViewLogRepository.findByArticleIdSnapshotOrderByViewedAtDesc(articleId);
        assertEquals(1, views.size(), "the view log must still be reachable by the deleted article's id");
        assertNull(views.get(0).getArticleId(), "the FK is still nulled -- that is how you know it is gone");
        assertEquals("წასაშლელი სტატია", views.get(0).getArticleTitleSnapshot());

        assertTrue(articleReadReceiptRepository
                        .findByArticleIdSnapshotAndArticleVersionAndOperatorId(articleId, 1, operator.getId())
                        .isPresent(),
                "the read receipt must still be reachable by the deleted article's id");
    }

    @Test
    void viewsReportsTotalAndUniqueCountsWithPagination() throws Exception {
        User admin = createUser("aa54@magti.ge", Role.SYSTEM_ADMIN, "All");
        Category cat = createCategory("კატ-40");
        long articleId = createArticleViaApi(tokenFor(admin), "ნანახი სტატია", "შინაარსი", cat.getId());
        User viewer1 = createUser("aa55@magti.ge", Role.OPERATOR, "All");
        User viewer2 = createUser("aa56@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(viewer1))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(viewer1))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(viewer2))).andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/views"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_views").value(3))
                .andExpect(jsonPath("$.unique_viewers").value(2))
                .andExpect(jsonPath("$.views.length()").value(3));

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/views").param("limit", "1"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views.length()").value(1));
    }

    @Test
    void contentManagerGetsOnlyArticleAggregateWithoutNamedReceipts() throws Exception {
        String department = "აგრეგატი-" + System.nanoTime();
        User contentManager = createUser("evidence-aggregate@magti.ge", Role.CONTENT_ADMIN, department);
        Category category = createCategory("აგრეგატი-კატეგორია");
        long articleId = createArticleViaApiWithDept(
                tokenFor(contentManager), "აგრეგატული მტკიცებულება", "შინაარსი", category.getId(), department);
        User reader = createUser("evidence-reader@magti.ge", Role.OPERATOR, department);
        createUser("evidence-unread@magti.ge", Role.OPERATOR, department);

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(reader)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipts"), tokenFor(contentManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible_count").value(2))
                .andExpect(jsonPath("$.read_count").value(1))
                .andExpect(jsonPath("$.unread_count").value(1))
                .andExpect(jsonPath("$.receipts.length()").value(0));
    }

    @Test
    void groupLeaderSeesNamedOfficialReadsOnlyForAssignedTeam() throws Exception {
        String department = "ლიდერის-scope-" + System.nanoTime();
        User publisher = createUser("evidence-publisher@magti.ge", Role.CONTENT_ADMIN, department);
        Category category = createCategory("scope-კატეგორია");
        long articleId = createArticleViaApiWithDept(
                tokenFor(publisher), "scope მტკიცებულება", "შინაარსი", category.getId(), department);

        Team ownTeam = createTeam("scope-own-" + System.nanoTime());
        Team siblingTeam = createTeam("scope-sibling-" + System.nanoTime());
        User leader = createUser("evidence-leader@magti.ge", Role.MANAGER, department);
        leader.setTeamId(ownTeam.getId());
        leader = userRepository.saveAndFlush(leader);
        leadTeam(leader, ownTeam, AssignmentType.ACTING);

        User ownReader = createUser("evidence-own@magti.ge", Role.OPERATOR, department);
        ownReader.setTeamId(ownTeam.getId());
        ownReader = userRepository.saveAndFlush(ownReader);
        User siblingReader = createUser("evidence-sibling@magti.ge", Role.OPERATOR, department);
        siblingReader.setTeamId(siblingTeam.getId());
        siblingReader = userRepository.saveAndFlush(siblingReader);

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(ownReader)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/read-receipt"), tokenFor(siblingReader)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipts"), tokenFor(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible_count").value(1))
                .andExpect(jsonPath("$.read_count").value(1))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].operator_id").value(ownReader.getId()))
                .andExpect(jsonPath("$.receipts[0].operator_email").doesNotExist());
    }

    @Test
    void unassignedCallerCannotReadNamedEvidenceAndContentAdminCannotReadViewLogs() throws Exception {
        User publisher = createUser("evidence-denied-publisher@magti.ge", Role.CONTENT_ADMIN, "All");
        User unassigned = createUser("evidence-denied-user@magti.ge", Role.OPERATOR, "All");
        Category category = createCategory("უარყოფილი-evidence");
        long articleId = createArticleViaApi(
                tokenFor(publisher), "დაცული მტკიცებულება", "შინაარსი", category.getId());

        mockMvc.perform(authed(get("/api/articles/" + articleId + "/read-receipts"), tokenFor(unassigned)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/articles/" + articleId + "/views"), tokenFor(publisher)))
                .andExpect(status().isForbidden());
    }

    @Test
    void recentlyViewedDedupesRepeatViews() throws Exception {
        User admin = createUser("aa57@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-41");
        long articleA = createArticleViaApi(tokenFor(admin), "სტატია ა", "შინაარსი ა", cat.getId());
        long articleB = createArticleViaApi(tokenFor(admin), "სტატია ბ", "შინაარსი ბ", cat.getId());
        User operator = createUser("aa58@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/articles/" + articleA + "/view"), tokenFor(operator))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleB + "/view"), tokenFor(operator))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + articleA + "/view"), tokenFor(operator))).andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/me/recently-viewed"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("სტატია ა"))
                .andExpect(jsonPath("$[1].title").value("სტატია ბ"));
    }

    /**
     * BL-11 -- the audit's only SUSPECTED finding. It could not be executed
     * there ("no Oracle available in this container"), so it was recorded as
     * a read of the code plus the constraint rather than an observed
     * failure. CONFIRMED here, against a real database.
     *
     * <p>The race: article_history is UNIQUE on (article_id, version_id)
     * (V18:15), and both updateArticle and restoreArticleVersion compute the
     * next version as {@code getVersion() + 1} from a row read earlier in
     * the same request. Two admins saving at the same instant both compute
     * N+1; the second violates the constraint. Before the optimistic lock
     * that was a bare 500 with the second editor's work gone and nothing
     * explaining why.
     *
     * <p>Simulated deterministically rather than with threads: a detached
     * copy IS a stale read, which is the only thing the race actually
     * depends on. Real concurrency would add flakiness without adding proof.
     */
    @Test
    void aStaleArticleWriteIsRejectedInsteadOfCollidingOnTheHistoryConstraint() throws Exception {
        User admin = createUser("bl11@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-21");
        long articleId = createArticleViaApi(tokenFor(admin), "ორი რედაქტორი", "საწყისი ტექსტი", cat.getId());

        // Editor A and editor B both open the article: two reads of the same
        // row, at the same version.
        entityManager.flush();
        entityManager.clear();
        Article editorBsCopy = articleRepository.findById(articleId).orElseThrow();
        entityManager.detach(editorBsCopy);

        // Editor A saves first, through the real endpoint.
        mockMvc.perform(authed(put("/api/articles/" + articleId), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ორი რედაქტორი\",\"content\":\"A-ს ვერსია\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"All\"],\"status\":\"published\","
                                + "\"is_draft\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        entityManager.flush();
        entityManager.clear();

        // Editor B now saves their stale copy. Without @Version this
        // succeeded and then blew up inserting a second history row at
        // version 2; with it, the UPDATE itself is refused.
        editorBsCopy.setContent("B-ს ვერსია");
        editorBsCopy.setVersion(editorBsCopy.getVersion() + 1);
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> {
                    articleRepository.save(editorBsCopy);
                    entityManager.flush();
                });

        // A's edit is intact -- the point of refusing B is that nobody's
        // work disappears silently.
        entityManager.clear();
        assertEquals("A-ს ვერსია", articleRepository.findById(articleId).orElseThrow().getContent());
    }

    /** The business version must keep behaving exactly as before -- the lock is a separate column for that reason. */
    @Test
    void theOptimisticLockDoesNotDisturbTheBusinessVersionSequence() throws Exception {
        User admin = createUser("bl11b@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-22");
        long articleId = createArticleViaApi(tokenFor(admin), "ვერსიების თანმიმდევრობა", "v1", cat.getId());

        for (int expectedVersion = 2; expectedVersion <= 4; expectedVersion++) {
            mockMvc.perform(authed(put("/api/articles/" + articleId), tokenFor(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ვერსიების თანმიმდევრობა\",\"content\":\"v" + expectedVersion
                                    + "\",\"category_id\":" + cat.getId()
                                    + ",\"target_departments\":[\"All\"],\"status\":\"published\","
                                    + "\"is_draft\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(expectedVersion));
        }
    }
    /**
     * RTA-003 end to end. ContentSanitizerTest proves the allowlist; this
     * proves the allowlist is actually on the path a content administrator
     * uses, for create and for update, so a later refactor that drops the
     * call fails here rather than in someone's browser.
     */
    @Test
    void hostileArticleContentIsNeutralisedOnCreateAndUpdate() throws Exception {
        User admin = createUser("xss1@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        Category cat = createCategory("კატ-XSS");
        String hostile = "<p>ტექსტი</p><img src=\\\"/uploads/a.png\\\" onerror=\\\"steal()\\\">"
                + "<script>steal(localStorage.magti_token)</script>";

        String body = mockMvc.perform(authed(post("/api/articles"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"XSS\",\"content\":\"" + hostile + "\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"All\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();

        String stored = articleRepository.findById(id).orElseThrow().getContent();
        assertFalse(stored.contains("onerror"), stored);
        assertFalse(stored.contains("<script"), stored);
        assertFalse(stored.contains("steal"), stored);
        // The legitimate parts survive -- a sanitizer that emptied the body
        // would pass every assertion above and still be a data-loss bug.
        assertTrue(stored.contains("ტექსტი"), stored);
        assertTrue(stored.contains("src=\"/uploads/a.png\""), stored);

        mockMvc.perform(authed(put("/api/articles/" + id), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"XSS\",\"content\":\"" + hostile + "\",\"category_id\":"
                                + cat.getId() + ",\"target_departments\":[\"All\"]}"))
                .andExpect(status().isOk());
        assertFalse(articleRepository.findById(id).orElseThrow().getContent().contains("onerror"));
    }

}
