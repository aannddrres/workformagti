package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleHistoryRepository;
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
 * CategoryControllerIntegrationTest}. Covers this slice's core CRUD +
 * lifecycle only (list/get/create/update/autosave/delete/archive/unarchive/
 * bulk-archive); history/diff/restore, quiz, and read-receipts/views are
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

        mockMvc.perform(authed(get("/api/articles"), tokenFor(admin)))
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

        mockMvc.perform(authed(get("/api/articles"), tokenFor(operator)))
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
}
