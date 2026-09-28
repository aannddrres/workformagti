package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.ArticleViewLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.NewsHistoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PO-34 and the owner's D2 (2026-09-25): another author's private draft
 * ({@code is_draft}) is nobody else's -- not a content administrator's, not a
 * system administrator's, not a holder of {@code content.manage} -- on any
 * endpoint.
 *
 * <p>{@link ge.magti.portal.article.ArticleVisibility} already said so for the
 * read endpoints that ask it. This sweeps the rest: the ones that change an
 * article (their responses carry the whole article, content included), the
 * bulk operations (bulk-status clears {@code is_draft}, so it could publish a
 * colleague's autosave to the company), the evidence endpoints, and the lists
 * that name articles by title.
 *
 * <p>Each other editor is also shown doing the same thing to an ordinary
 * article, so a 404 here means "not yours", never "not allowed at all".
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PrivateDraftIsolationIntegrationTest {

    private static final String DRAFT_TITLE = "პირადიმონახაზი ზღარბი";
    private static final String LEGACY_TITLE = "პირადიმონახაზი ძველი";
    private static final String SEARCH_WORD = "პირადიმონახაზი";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserPermissionOverrideRepository overrides;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleTargetDepartmentRepository targets;
    @Autowired private ArticleViewLogRepository viewLogs;
    @Autowired private NewsRepository newsRepository;
    @Autowired private NewsHistoryRepository newsHistory;
    @Autowired private SearchReindexService searchReindexService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManager entityManager;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * The author, the three kinds of other editor, and three articles: the
     * author's private draft, a legacy row whose status says published while
     * is_draft is still set (what the 2026-09-06 fix found in real data), and
     * an ordinary published article in the same category.
     */
    private record Fixture(User author, List<User> others, Category category, Category otherCategory,
                           Article draft, Article legacy, Article published) {
    }

    private Fixture fixture(String tag) {
        User author = user(tag + "-author", Role.CONTENT_ADMIN);
        User contentAdmin = user(tag + "-content", Role.CONTENT_ADMIN);
        User systemAdmin = user(tag + "-system", Role.SYSTEM_ADMIN);
        User permissionHolder = user(tag + "-holder", Role.OPERATOR);
        for (Permission permission : List.of(Permission.CONTENT_MANAGE, Permission.ARTICLES_EDIT,
                Permission.ARTICLES_ARCHIVE, Permission.COMPLIANCE_ASSIGN)) {
            UserPermissionOverride allow = new UserPermissionOverride();
            allow.setUserId(permissionHolder.getId());
            allow.setPermission(permission.value());
            allow.setState(UserPermissionOverride.State.ALLOW);
            allow.setUpdatedAt(TbilisiTime.now());
            allow.setUpdatedBy(author.getId());
            overrides.saveAndFlush(allow);
        }
        Category category = category(tag + "-კატ");
        Category otherCategory = category(tag + "-სხვა-კატ");
        OffsetDateTime longAgo = TbilisiTime.now().minusDays(400);
        Article draft = article(DRAFT_TITLE, category, "draft", true, null, author, TbilisiTime.now());
        Article legacy = article(LEGACY_TITLE, category, "published", true, longAgo, author, longAgo);
        Article published = article(tag + " ჩვეულებრივი", category, "published", false, longAgo, author, longAgo);
        return new Fixture(author, List.of(contentAdmin, systemAdmin, permissionHolder),
                category, otherCategory, draft, legacy, published);
    }

    // ── single-article changes ─────────────────────────────────────────

    @Test
    void noOtherEditorCanChangeAnotherAuthorsPrivateDraft() throws Exception {
        Fixture f = fixture("pd-change");
        long id = f.draft().getId();
        for (User other : f.others()) {
            String token = token(other);
            String who = other.getEmail();
            expectNotFound(authed(put("/api/articles/" + id), token)
                    .contentType(MediaType.APPLICATION_JSON).content(articleBody(f.category(), "გადაწერილი")), who);
            expectNotFound(authed(patch("/api/articles/" + id + "/autosave"), token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"გადაწერილი\"}"), who);
            expectNotFound(authed(put("/api/articles/" + id + "/command"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"article\":" + articleBody(f.category(), "გადაწერილი") + ",\"mandatory\":false}"), who);
            expectNotFound(authed(post("/api/articles/" + id + "/archive"), token), who);
            expectNotFound(authed(post("/api/articles/" + id + "/unarchive"), token), who);
            expectNotFound(authed(post("/api/articles/" + id + "/verify"), token), who);
            expectNotFound(authed(delete("/api/articles/" + id), token), who);

            // The same editor may do the same thing to an ordinary article.
            assertEquals(200, call(authed(post("/api/articles/" + f.published().getId() + "/verify"), token)).getStatus(),
                    who + " must pass the gate itself");
        }

        entityManager.clear();
        Article after = articleRepository.findById(id).orElseThrow();
        assertEquals(DRAFT_TITLE, after.getTitle());
        assertEquals("draft", after.getStatus());
        assertTrue(after.isDraft());
        assertEquals(null, after.getLastVerifiedAt(), "verify must not have touched it");

        // The author still can.
        assertEquals(200, call(authed(put("/api/articles/" + id), token(f.author()))
                .contentType(MediaType.APPLICATION_JSON).content(articleBody(f.category(), DRAFT_TITLE))).getStatus());
    }

    @Test
    void bulkOperationsSkipAnotherAuthorsPrivateDraft() throws Exception {
        Fixture f = fixture("pd-bulk");
        long id = f.draft().getId();
        for (User other : f.others()) {
            String token = token(other);
            JsonNode status = body(call(authed(post("/api/articles/bulk-status"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[" + id + "],\"status\":\"published\"}")), 200, other);
            assertEquals(0, status.path("updated").asInt(), other.getEmail());
            assertTrue(ids(status.path("skipped_ids")).contains(id), other.getEmail());

            JsonNode archive = body(call(authed(post("/api/articles/bulk-archive"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[" + id + "],\"archive\":true}")), 200, other);
            assertTrue(ids(archive.path("skipped_ids")).contains(id), other.getEmail());

            JsonNode retarget = body(call(authed(post("/api/articles/bulk-retarget"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[" + id + "],\"category_id\":" + f.otherCategory().getId() + "}")), 200, other);
            assertEquals(0, retarget.path("updated").asInt(), other.getEmail());
            assertTrue(ids(retarget.path("skipped_ids")).contains(id), other.getEmail());
        }

        entityManager.clear();
        Article after = articleRepository.findById(id).orElseThrow();
        assertTrue(after.isDraft(), "bulk-status clears is_draft; that would have published it");
        assertEquals("draft", after.getStatus());
        assertEquals(f.category().getId(), after.getCategoryId());

        // An ordinary article in the same request is still moved.
        JsonNode mixed = body(call(authed(post("/api/articles/bulk-status"), token(f.others().get(0)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + id + "," + f.published().getId() + "],\"status\":\"archived\"}")), 200,
                f.others().get(0));
        assertEquals(1, mixed.path("updated").asInt());
        assertEquals(List.of(id), ids(mixed.path("skipped_ids")));
    }

    // ── evidence ───────────────────────────────────────────────────────

    @Test
    void readEvidenceOfAnotherAuthorsPrivateDraftIsNotFound() throws Exception {
        Fixture f = fixture("pd-evidence");
        long id = f.draft().getId();
        for (User other : f.others()) {
            String token = token(other);
            MockHttpServletResponse receipts = call(authed(get("/api/articles/" + id + "/read-receipts"), token));
            assertNotEquals(200, receipts.getStatus(), other.getEmail() + " read-receipts");
            assertFalse(receipts.getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE));
            MockHttpServletResponse views = call(authed(get("/api/articles/" + id + "/views"), token));
            assertNotEquals(200, views.getStatus(), other.getEmail() + " views");
            if (other.getRole() == Role.SYSTEM_ADMIN) {
                assertEquals(404, receipts.getStatus());
                assertEquals(404, views.getStatus(), "views passes its SYSTEM_ADMIN gate, then must not find it");
            }
            assertEquals("null", call(authed(get("/api/compliance/required-readings/by-item/article/" + id), token))
                    .getContentAsString(StandardCharsets.UTF_8), other.getEmail() + " by-item");
        }
    }

    // ── lists that name articles ───────────────────────────────────────

    @Test
    void anotherAuthorsPrivateDraftsAreNamedInNoList() throws Exception {
        Fixture f = fixture("pd-lists");
        Long draft = f.draft().getId();
        Long legacy = f.legacy().getId();
        for (User other : f.others()) {
            String token = token(other);
            List<Long> related = ids(body(call(authed(get("/api/articles/" + f.published().getId() + "/related"), token)),
                    200, other), "id");
            assertFalse(related.contains(draft) || related.contains(legacy), other.getEmail() + " related " + related);

            MockHttpServletResponse stale = call(authed(get("/api/admin/articles/stale"), token));
            assertEquals(200, stale.getStatus(), other.getEmail());
            assertFalse(ids(json.readTree(stale.getContentAsString(StandardCharsets.UTF_8)), "id").contains(legacy), other.getEmail() + " stale");

            List<Long> found = ids(body(call(authed(get("/api/search"), token)
                    .param("q", SEARCH_WORD).param("category_id", f.category().getId().toString())), 200, other), "id");
            assertFalse(found.contains(draft) || found.contains(legacy), other.getEmail() + " search " + found);

            ArticleViewLog view = new ArticleViewLog();
            view.setArticleId(draft);
            view.setArticleIdSnapshot(draft);
            view.setArticleTitleSnapshot(DRAFT_TITLE);
            view.setArticleVersion(1);
            view.setOperatorId(other.getId());
            view.setOperatorNameSnapshot(other.getName());
            view.setOperatorEmailSnapshot(other.getEmail());
            view.setOperatorDepartmentSnapshot(other.getDepartment());
            view.setViewedAt(TbilisiTime.now());
            viewLogs.saveAndFlush(view);
            List<Long> recent = ids(body(call(authed(get("/api/me/recently-viewed"), token)), 200, other), "article_id");
            assertFalse(recent.contains(draft), other.getEmail() + " recently viewed " + recent);
        }

        // The author finds both of their own.
        List<Long> own = ids(body(call(authed(get("/api/search"), token(f.author()))
                .param("q", SEARCH_WORD).param("category_id", f.category().getId().toString())), 200, f.author()), "id");
        assertTrue(own.contains(draft) && own.contains(legacy), "author search " + own);
    }

    /**
     * Global search caches each answer for 60 s. Asked by the author first,
     * the answer holds the author's drafts; a key shared by role and
     * department then served it to the next colleague with the same two.
     */
    @Test
    void globalSearchDoesNotServeTheAuthorsAnswerToAColleague() throws Exception {
        Fixture f = fixture("pd-global");
        String query = SEARCH_WORD + " ზღარბი";
        List<Long> authors = ids(body(call(authed(get("/api/search/global"), token(f.author())).param("q", query)),
                200, f.author()).path("articles"), "id");
        assertTrue(authors.contains(f.draft().getId()), "the author's own answer " + authors);
        for (User other : f.others()) {
            MockHttpServletResponse theirs = call(authed(get("/api/search/global"), token(other)).param("q", query));
            assertEquals(200, theirs.getStatus());
            assertFalse(theirs.getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE),
                    other.getEmail() + " was served the author's cached answer");
        }
    }

    @Test
    void anotherAuthorsPrivateDraftCannotBeAssignedOrBookmarkedByItsTitle() throws Exception {
        Fixture f = fixture("pd-assign");
        long id = f.draft().getId();
        for (User other : f.others()) {
            String token = token(other);
            expectNotFound(authed(post("/api/compliance/required-readings"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item_type\":\"article\",\"item_id\":" + id + ",\"target_department\":\"All\","
                            + "\"due_date\":\"" + TbilisiTime.now().plusDays(7) + "\",\"priority\":\"high\"}"),
                    other.getEmail());

            MockHttpServletResponse bookmarked = call(authed(post("/api/favorites"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item_type\":\"article\",\"item_id\":" + id + "}"));
            assertEquals(200, bookmarked.getStatus(), other.getEmail());
            assertFalse(bookmarked.getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE), other.getEmail() + " favorite");
            assertFalse(call(authed(get("/api/favorites"), token)).getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE),
                    other.getEmail() + " favorites list");
        }
    }

    // ── news ───────────────────────────────────────────────────────────

    /**
     * D2 names is_draft, not articles: a news item carries the same flag, and
     * NewsVisibility already gives GET /api/news/{id} the author-only answer.
     * Its changes and its history answered anyone holding content.manage.
     */
    @Test
    void anotherAuthorsPrivateNewsDraftIsReachableFromNoEndpoint() throws Exception {
        Fixture f = fixture("pd-news");
        News draft = news(DRAFT_TITLE, true, f.author());
        News published = news("pd-news ჩვეულებრივი", false, f.author());
        // One saved revision, so the history item and restore have a target.
        assertEquals(200, call(authed(put("/api/news/" + draft.getId()), token(f.author()))
                .contentType(MediaType.APPLICATION_JSON).content(newsBody(DRAFT_TITLE))).getStatus());
        long historyId = newsHistory.findByNewsIdOrderByUpdatedAtDesc(draft.getId(), PageRequest.of(0, 1))
                .getFirst().getId();
        String base = "/api/news/" + draft.getId();

        for (User other : f.others()) {
            String token = token(other);
            String who = other.getEmail();
            expectNotFound(authed(put(base), token)
                    .contentType(MediaType.APPLICATION_JSON).content(newsBody("გადაწერილი")), who);
            expectNotFound(authed(put(base + "/command"), token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"news\":" + newsBody("გადაწერილი") + ",\"mandatory\":false}"), who);
            expectNotFound(authed(patch(base + "/autosave"), token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"გადაწერილი\"}"), who);
            expectNotFound(authed(post(base + "/archive"), token), who);
            expectNotFound(authed(post(base + "/unarchive"), token), who);
            expectNotFound(authed(delete(base), token), who);
            expectNotFound(authed(get(base + "/history"), token), who);
            expectNotFound(authed(get(base + "/history-summary"), token), who);
            expectNotFound(authed(get(base + "/history/" + historyId), token), who);
            expectNotFound(authed(post(base + "/history/" + historyId + "/restore"), token), who);
            expectNotFound(authed(post("/api/compliance/required-readings"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item_type\":\"news\",\"item_id\":" + draft.getId() + ",\"target_department\":\"All\","
                            + "\"due_date\":\"" + TbilisiTime.now().plusDays(7) + "\",\"priority\":\"high\"}"), who);
            MockHttpServletResponse bookmarked = call(authed(post("/api/favorites"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item_type\":\"news\",\"item_id\":" + draft.getId() + "}"));
            assertEquals(200, bookmarked.getStatus(), who);
            assertFalse(bookmarked.getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE), who + " favorite");

            // The same editor may archive an ordinary news item.
            assertEquals(200, call(authed(post("/api/news/" + published.getId() + "/archive"), token)).getStatus(),
                    who + " must pass the gate itself");
        }

        entityManager.clear();
        News after = newsRepository.findById(draft.getId()).orElseThrow();
        assertEquals(DRAFT_TITLE, after.getTitle());
        assertTrue(after.isDraft());
        assertEquals(null, after.getExpiresAt(), "archive must not have touched it");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private News news(String title, boolean isDraft, User author) {
        News news = new News();
        news.setTitle(title);
        news.setContent("სიახლე, რომელიც მხოლოდ ავტორმა უნდა ნახოს");
        news.setTargetDepartment("All");
        news.setDraft(isDraft);
        news.setAuthorId(author.getId());
        news.setCreatedAt(TbilisiTime.now());
        return newsRepository.saveAndFlush(news);
    }

    private static String newsBody(String title) {
        return "{\"title\":\"" + title + "\",\"content\":\"შინაარსი\",\"target_department\":\"All\"}";
    }

    private User user(String local, Role role) {
        User user = new User();
        user.setEmail(local + "@magti.ge");
        user.setName(local);
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value).collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private Article article(String title, Category category, String status, boolean isDraft,
                            OffsetDateTime publishedAt, User author, OffsetDateTime verifiedAt) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი, რომელიც მხოლოდ ავტორმა უნდა ნახოს");
        article.setCategoryId(category.getId());
        article.setTags("zzpd");
        article.setStatus(status);
        article.setDraft(isDraft);
        article.setPublishedAt(publishedAt);
        article.setAuthorId(author.getId());
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        if (!"draft".equals(status)) {
            article.setLastVerifiedAt(verifiedAt);
        }
        Article saved = articleRepository.saveAndFlush(article);
        ArticleTargetDepartment target = new ArticleTargetDepartment();
        target.setArticleId(saved.getId());
        target.setDepartment("All");
        targets.saveAndFlush(target);
        searchReindexService.reindexArticle(saved);
        return saved;
    }

    private String articleBody(Category category, String title) {
        return "{\"title\":\"" + title + "\",\"content\":\"შინაარსი\",\"category_id\":" + category.getId()
                + ",\"target_departments\":[\"All\"],\"status\":\"draft\",\"is_draft\":true}";
    }

    private String token(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void expectNotFound(MockHttpServletRequestBuilder request, String who) throws Exception {
        MockHttpServletResponse response = call(request);
        assertEquals(404, response.getStatus(), who + ": " + response.getContentAsString(StandardCharsets.UTF_8));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains(DRAFT_TITLE), who + " saw the title");
    }

    private JsonNode body(MockHttpServletResponse response, int expected, User who) throws Exception {
        assertEquals(expected, response.getStatus(), who.getEmail() + ": " + response.getContentAsString(StandardCharsets.UTF_8));
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.asLong()));
        return ids;
    }

    private static List<Long> ids(JsonNode array, String field) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path(field).asLong()));
        return ids;
    }
}
