package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.ArticleReadReceipt;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserNote;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserNoteRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * and the small standalone endpoints (deprecated feedback stubs, notes,
 * verify, stale report, related); history/diff/restore, quiz (its own
 * {@link QuizControllerIntegrationTest}), and read-receipts/views are
 * later slices with their own tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
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
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));
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
        admin.setPermissions(new java.util.LinkedHashSet<>(admin.getPermissions()));
        admin.getPermissions().remove(Permission.ARTICLES_EDIT.value());
        userRepository.saveAndFlush(admin);

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
        editorNoPublish.setPermissions(new java.util.LinkedHashSet<>(editorNoPublish.getPermissions()));
        editorNoPublish.getPermissions().remove(Permission.ARTICLES_PUBLISH.value());
        userRepository.saveAndFlush(editorNoPublish);

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

    // ── delete ────────────────────────────────────────────────────────

    @Test
    void deletingAnArticleCascadesHistoryAndTargetDepartments() throws Exception {
        User admin = createUser("aa14@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-12");
        Article article = createArticle("წასაშლელი", cat.getId(), "published", false,
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
        // The DELETE's own pending change is on `articles`; Hibernate's
        // auto-flush-before-query only flushes when a query's own table
        // overlaps what's dirty, so a query against a DIFFERENT table
        // (article_target_departments/article_history) won't by itself
        // trigger flushing the articles DELETE first. Forcing it here
        // makes this verification see what Oracle's ON DELETE CASCADE
        // actually did -- already independently confirmed directly via
        // sqlplus. A real caller never needs this: in production the
        // request's own transaction commits right after the controller
        // method returns, so any later request already sees the true
        // post-cascade state without help.
        articleRepository.flush();

        assertTrue(articleRepository.findById(article.getId()).isEmpty());
        assertTrue(targetDepartmentRepository.findByArticleId(article.getId()).isEmpty());
        // Not findById(history.getId()) -- that checks Hibernate's L1
        // session cache first and returns the same still-held Java object
        // this test constructed, without ever re-querying, regardless of
        // the flush above. findByArticleId always issues a real SELECT.
        assertTrue(articleHistoryRepository.findByArticleId(article.getId()).isEmpty());
    }

    /**
     * Bug found during the PM migration-gap audit (2026-08-11): V24 created
     * {@code fk_user_notes_article} without ON DELETE CASCADE (unlike its
     * siblings above), so deleting an article with an existing personal
     * note 500'd on ORA-02292. Fixed by V30. This is the regression test.
     */
    @Test
    void deletingAnArticleCascadesUserNotes() throws Exception {
        User admin = createUser("aa15@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("op15@magti.ge", Role.OPERATOR, "ტექნიკური");
        Category cat = createCategory("კატ-13");
        Article article = createArticle("პირადი შენიშვნის სტატია", cat.getId(), "published", false,
                List.of("All"), null);

        UserNote note = new UserNote();
        note.setUserId(operator.getId());
        note.setArticleId(article.getId());
        note.setContent("ჩემი შენიშვნა");
        userNoteRepository.saveAndFlush(note);

        mockMvc.perform(authed(delete("/api/articles/" + article.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());
        articleRepository.flush();

        assertTrue(articleRepository.findById(article.getId()).isEmpty());
        assertTrue(userNoteRepository.findByUserIdAndArticleId(operator.getId(), article.getId()).isEmpty());
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

    // ── deprecated feedback stubs ─────────────────────────────────────

    @Test
    void feedbackEndpointsAreGoneButStillGateOnAuth() throws Exception {
        mockMvc.perform(post("/api/articles/1/feedback"))
                .andExpect(status().isUnauthorized());

        User operator = createUser("aa20@magti.ge", Role.OPERATOR, "All");
        mockMvc.perform(authed(post("/api/articles/1/feedback"), tokenFor(operator)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("ხარვეზის რეპორტირება დეპრეკირებულია"));

        mockMvc.perform(authed(get("/api/admin/feedback"), tokenFor(operator)))
                .andExpect(status().isForbidden());

        User admin = createUser("aa21@magti.ge", Role.CONTENT_ADMIN, "All");
        mockMvc.perform(authed(get("/api/admin/feedback"), tokenFor(admin)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("უკუკავშირის ნახვა დეპრეკირებულია"));
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
        User admin = createUser("aa40@magti.ge", Role.CONTENT_ADMIN, "All");
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
        // Keyed by email, not operator_name: this file's createUser gives
        // every test user the identical fixed display name, unlike
        // QuizControllerIntegrationTest's helper (which interpolates the
        // email) -- keying by name here would collapse all three rows onto
        // one map entry.
        Map<String, com.fasterxml.jackson.databind.JsonNode> byEmail = new java.util.HashMap<>();
        receipts.forEach(r -> byEmail.put(r.get("operator_email").asText(), r));

        assertTrue(byEmail.get(reader.getEmail()).get("has_read").asBoolean());
        assertFalse(byEmail.get(nonReader.getEmail()).get("has_read").asBoolean());
        assertTrue(byEmail.get(formerReader.getEmail()).get("has_read").asBoolean(),
                "an orphaned/detached snapshot row must still show as read");
    }

    @Test
    void readReceiptsMarksLateReadsPastTheDueDate() throws Exception {
        User admin = createUser("aa44@magti.ge", Role.CONTENT_ADMIN, "All");
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
    void viewTrackingHasNoVisibilityGateUnlikeMostEndpoints() throws Exception {
        User admin = createUser("aa52@magti.ge", Role.CONTENT_ADMIN, "All");
        Category cat = createCategory("კატ-39");
        long articleId = createArticleViaApiWithDept(
                tokenFor(admin), "ტექნიკურის სტატია", "შინაარსი", cat.getId(), "ტექნიკური");
        User unrelatedOperator = createUser("aa53@magti.ge", Role.OPERATOR, "საინფორმაციო");

        // Confirm the operator genuinely can't see it via the normal read path...
        mockMvc.perform(authed(get("/api/articles/" + articleId), tokenFor(unrelatedOperator)))
                .andExpect(status().isNotFound());

        // ...but view-tracking still succeeds anyway.
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), tokenFor(unrelatedOperator)))
                .andExpect(status().isOk());

        assertEquals(1, articleViewLogRepository.findByArticleIdOrderByViewedAtDesc(articleId).size());
    }

    @Test
    void viewsReportsTotalAndUniqueCountsWithPagination() throws Exception {
        User admin = createUser("aa54@magti.ge", Role.CONTENT_ADMIN, "All");
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
}
