package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.*;
import ge.magti.portal.repository.*;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Private content must stay opaque even to another editor, including write responses. */
@RequiresOracle
@SpringBootTest(properties = "portal.retention.legal-hold-authorized-emails=private-authority@magti.ge")
@AutoConfigureMockMvc
@Transactional
class PrivateContentBoundaryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ArticleRepository articles;
    @Autowired CategoryRepository categories;
    @Autowired ArticleHistoryRepository articleHistory;
    @Autowired NewsRepository news;
    @Autowired NewsHistoryRepository newsHistory;
    @Autowired RequiredReadingRepository readings;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private User user(Role role) {
        User row = new User();
        row.setEmail("private-boundary-" + System.nanoTime() + "@magti.ge");
        row.setName("პირადი მონახაზის ტესტი");
        row.setRole(role);
        row.setDepartment("All");
        row.setActive(true);
        row.setHashedPassword("unused");
        row.setPermissions(Permission.defaultsFor(role).stream().map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return users.saveAndFlush(row);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(
                Map.of("sub", user.getEmail(), "role", user.getRole().value())));
    }

    private Article article(User author) {
        Category category = new Category();
        category.setName("პირადი კატეგორია " + System.nanoTime());
        category = categories.saveAndFlush(category);
        Article row = new Article();
        row.setCategoryId(category.getId());
        row.setTitle("პირადი სათაური");
        row.setContent("პირადი შინაარსი");
        row.setAuthorId(author.getId());
        row.setDraft(true);
        row.setStatus("draft");
        row.setCreatedAt(TbilisiTime.now());
        row.setUpdatedAt(TbilisiTime.now());
        return articles.saveAndFlush(row);
    }

    private long history(Article article, User author) {
        ArticleHistory row = new ArticleHistory();
        row.setArticleId(article.getId());
        row.setTitle(article.getTitle());
        row.setContent(article.getContent());
        row.setVersionId(1);
        row.setUpdatedBy(author.getId());
        row.setUpdatedAt(TbilisiTime.now());
        return articleHistory.saveAndFlush(row).getId();
    }

    private long count(String table, String column, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    }

    @Test
    void privateTrashIsVisibleAndRestorableOnlyByItsAuthor() throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(Role.SYSTEM_ADMIN);
        other.setEmail("private-authority@magti.ge");
        users.saveAndFlush(other);
        Article article = article(author);
        News item = new News();
        item.setTitle("სანაგვის პირადი სიახლე");
        item.setContent("პირადი შინაარსი");
        item.setAuthorId(author.getId());
        item.setDraft(true);
        item.setCreatedAt(TbilisiTime.now());
        item = news.saveAndFlush(item);
        long articleHistoryId = history(article, author);
        NewsHistory newsSnapshot = new NewsHistory();
        newsSnapshot.setNewsId(item.getId());
        newsSnapshot.setTitle(item.getTitle());
        newsSnapshot.setContent(item.getContent());
        newsSnapshot.setUpdatedBy(author.getId());
        newsSnapshot.setUpdatedAt(TbilisiTime.now());
        long newsHistoryId = newsHistory.saveAndFlush(newsSnapshot).getId();
        for (var target : Map.of("article", article.getId(), "news", item.getId()).entrySet()) {
            String type = target.getKey();
            long id = target.getValue();
            String table = type.equals("article") ? "articles" : "news";
            jdbc.update("UPDATE " + table + " SET trashed_at = CURRENT_TIMESTAMP, "
                    + "purge_after = CURRENT_TIMESTAMP + INTERVAL '30' DAY, trashed_by = ? WHERE id = ?",
                    author.getId(), id);
            em.clear();
            String contentPath = "/api/" + (type.equals("article") ? "articles" : "news") + "/" + id;
            long snapshotId = type.equals("article") ? articleHistoryId : newsHistoryId;
            for (String suffix : List.of("/history", "/history-summary", "/history/" + snapshotId)) {
                mvc.perform(as(get(contentPath + suffix), other)).andExpect(status().isNotFound());
                mvc.perform(as(get(contentPath + suffix), author)).andExpect(status().isOk());
            }
            String path = "/api/content-trash/" + type + "/" + id;
            mvc.perform(as(get("/api/content-trash"), other)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.item_type == '" + type + "' && @.item_id == " + id + ")]").isEmpty());
            mvc.perform(as(get("/api/content-trash"), author)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.item_type == '" + type + "' && @.item_id == " + id + ")]").isNotEmpty());
            // Restore only: legal hold and purge reach a private draft too --
            // legal and retention duties, not an editor's (A3, 2026-09-26).
            mvc.perform(as(post(path + "/restore"), other)).andExpect(status().isNotFound());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                    + " WHERE id = ? AND trashed_at IS NOT NULL AND legal_hold = 0", Integer.class, id));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE item_type = ? AND item_id = ?", Integer.class, type, id));
            mvc.perform(as(post(path + "/restore"), author)).andExpect(status().isOk());
        }
    }

    @Test
    void legacyPrivatePublishedRowsStayOutOfEditorialReferenceLists() throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(Role.CONTENT_ADMIN);
        Article article = article(author);
        article.setStatus("published");
        article.setLastVerifiedAt(TbilisiTime.now().minusYears(1));
        articles.saveAndFlush(article);
        mvc.perform(as(get("/api/admin/articles/stale"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + article.getId() + ")]").isEmpty());
        assertTrue(articles.findReferencesByStatus("published", other.getId(), org.springframework.data.domain.PageRequest.of(0, 100))
                .stream().noneMatch(row -> row.id().equals(article.getId())));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OPERATOR", "CONTENT_ADMIN", "SYSTEM_ADMIN"})
    void privateTitlesStayOutOfSummariesFavoritesAndAssignments(Role role) throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(role);
        Article article = article(author);
        article.setDraft(false);
        article.setStatus("published");
        articles.saveAndFlush(article);
        jdbc.update("INSERT INTO article_target_departments (article_id, department) VALUES (?, 'All')", article.getId());
        mvc.perform(as(post("/api/articles/" + article.getId() + "/view"), other)).andExpect(status().isOk());
        article.setDraft(true);
        article.setStatus("draft");
        article.setTitle("საიდუმლო შეცვლილი სათაური");
        articles.saveAndFlush(article);
        News item = new News();
        item.setTitle("საიდუმლო სიახლის სათაური");
        item.setContent("საიდუმლო შინაარსი");
        item.setAuthorId(author.getId());
        item.setDraft(true);
        item.setTargetDepartment("All");
        item.setCreatedAt(TbilisiTime.now());
        item = news.saveAndFlush(item);
        for (var target : Map.of("article", article.getId(), "news", item.getId()).entrySet()) {
            RequiredReading assignment = new RequiredReading();
            assignment.setItemType(target.getKey());
            assignment.setItemId(target.getValue());
            assignment.setItemTitleSnapshot("საიდუმლო ძველი სათაური");
            assignment.setTargetDepartment("All");
            assignment.setDueDate(TbilisiTime.now().plusDays(1));
            assignment = readings.saveAndFlush(assignment);
            mvc.perform(as(post("/api/favorites"), other).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("item_type", target.getKey(), "item_id", target.getValue()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.item_title").value("მასალა #" + target.getValue()));
            // PO-40: an obligation over an item the reader cannot open is not in
            // force -- 409 with a fixed sentence, no title, nothing recorded.
            mvc.perform(as(post("/api/compliance/mark-read/" + assignment.getId()), other))
                    .andExpect(status().isConflict())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("საიდუმლო"))));
            if (role != Role.OPERATOR) {
                Map<String, Object> body = Map.of("item_type", target.getKey(), "item_id", target.getValue(),
                        "target_department", "All", "due_date", TbilisiTime.now().plusDays(1).toString());
                long before = readings.count();
                mvc.perform(as(post("/api/compliance/required-readings"), other)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                        .andExpect(status().isNotFound());
                // The by-item lookup answers a colleague's private draft the way
                // it answers an item with no assignment: a literal null.
                mvc.perform(as(get("/api/compliance/required-readings/by-item/" + target.getKey() + "/" + target.getValue()), other))
                        .andExpect(status().isOk()).andExpect(content().string("null"));
                mvc.perform(as(put("/api/compliance/required-readings/" + assignment.getId()), other)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                        .andExpect(status().isNotFound());
                mvc.perform(as(delete("/api/compliance/required-readings/" + assignment.getId()), other))
                        .andExpect(status().isNotFound());
                assertEquals(before, readings.count());
            }
        }
        List<String> leaking = new java.util.ArrayList<>();
        for (String path : List.of("/api/notifications/summary", "/api/compliance/my-readings",
                "/api/me/recently-viewed", "/api/favorites")) {
            String body = mvc.perform(as(get(path), other)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("საიდუმლო")) {
                leaking.add(path);
            }
        }
        assertEquals(List.of(), leaking, "these summaries name another author's private draft");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CONTENT_ADMIN", "SYSTEM_ADMIN"})
    void anotherEditorCannotReadOrMutatePrivateArticle(Role role) throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(role);
        Article article = article(author);
        long id = article.getId();
        long version = history(article, author);
        String path = "/api/articles/" + id;
        for (String suffix : List.of("", "/history", "/history-summary", "/history/" + version,
                "/history/" + version + "/diff", "/versions", "/read-receipts")) {
            mvc.perform(as(get(path + suffix), other)).andExpect(status().isNotFound())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(article.getTitle()))));
        }
        if (role == Role.SYSTEM_ADMIN) {
            mvc.perform(as(get(path + "/views"), other)).andExpect(status().isNotFound());
        }
        for (MockHttpServletRequestBuilder request : List.of(
                patch(path + "/autosave").content("{}"),
                patch(path + "/autosave").content("{\"is_draft\":false,\"content\":\"overwrite\"}"),
                put(path).content(json.writeValueAsString(Map.of("title", "overwrite", "content", "changed",
                        "category_id", article.getCategoryId(), "target_departments", List.of("All"),
                        "status", "draft", "is_draft", false))),
                post(path + "/archive"), post(path + "/unarchive"), post(path + "/verify"),
                post(path + "/history/" + version + "/restore"), delete(path))) {
            mvc.perform(as(request.contentType(MediaType.APPLICATION_JSON), other)).andExpect(status().isNotFound());
        }
        em.clear();
        Article unchanged = articles.findById(id).orElseThrow();
        assertEquals("პირადი შინაარსი", unchanged.getContent());
        assertEquals("draft", unchanged.getStatus());
        assertEquals(1, unchanged.getVersion());
        assertTrue(unchanged.isDraft());
        assertEquals(1, count("article_history", "article_id", id));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE item_type = 'article' AND item_id = ?", Long.class, id));
    }

    @Test
    void bulkCommandsTreatOtherAuthorsPrivateDraftLikeAnAbsentId() throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(Role.CONTENT_ADMIN);
        Article article = article(author);
        long id = article.getId();
        for (Map.Entry<String, Map<String, Object>> command : Map.of(
                "bulk-archive", Map.<String, Object>of("ids", List.of(id), "archive", true),
                "bulk-status", Map.<String, Object>of("ids", List.of(id), "status", "published"),
                "bulk-retarget", Map.<String, Object>of("ids", List.of(id), "target_departments", List.of("Other"))).entrySet()) {
            mvc.perform(as(post("/api/articles/" + command.getKey()), other)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(command.getValue())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(0))
                    .andExpect(jsonPath("$.skipped_ids[0]").value(id));
        }
        em.clear();
        assertTrue(articles.findById(id).orElseThrow().isDraft());
        assertEquals("draft", articles.findById(id).orElseThrow().getStatus());
    }

    @Test
    void authorCanAutosaveAndOtherEditorsCanStillEditSharedEditorialDrafts() throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(Role.CONTENT_ADMIN);
        Article article = article(author);
        String path = "/api/articles/" + article.getId() + "/autosave";
        mvc.perform(as(patch(path), author).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ავტორის ცვლილება\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("ავტორის ცვლილება"));
        article.setDraft(false);
        articles.saveAndFlush(article);
        mvc.perform(as(patch(path), other).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"საერთო ცვლილება\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("საერთო ცვლილება"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CONTENT_ADMIN", "SYSTEM_ADMIN"})
    void anotherEditorCannotReadOrMutatePrivateNews(Role role) throws Exception {
        User author = user(Role.CONTENT_ADMIN);
        User other = user(role);
        News row = new News();
        row.setTitle("პირადი სიახლე");
        row.setContent("სიახლის პირადი შინაარსი");
        row.setAuthorId(author.getId());
        row.setDraft(true);
        row.setCreatedAt(TbilisiTime.now());
        row = news.saveAndFlush(row);
        NewsHistory snapshot = new NewsHistory();
        snapshot.setNewsId(row.getId());
        snapshot.setTitle(row.getTitle());
        snapshot.setContent(row.getContent());
        snapshot.setUpdatedBy(author.getId());
        snapshot.setUpdatedAt(TbilisiTime.now());
        long historyId = newsHistory.saveAndFlush(snapshot).getId();
        String path = "/api/news/" + row.getId();
        for (String suffix : List.of("", "/history", "/history-summary", "/history/" + historyId)) {
            mvc.perform(as(get(path + suffix), other)).andExpect(status().isNotFound());
        }
        for (MockHttpServletRequestBuilder request : List.of(
                patch(path + "/autosave").content("{}"),
                patch(path + "/autosave").content("{\"is_draft\":false}"),
                put(path).content("{\"title\":\"overwrite\",\"content\":\"changed\",\"target_department\":\"All\"}"),
                post(path + "/archive"), post(path + "/unarchive"),
                post(path + "/history/" + historyId + "/restore"), delete(path))) {
            mvc.perform(as(request.contentType(MediaType.APPLICATION_JSON), other)).andExpect(status().isNotFound());
        }
        mvc.perform(as(patch(path + "/autosave"), author).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        em.clear();
        assertTrue(news.findById(row.getId()).orElseThrow().isDraft());
        assertEquals("სიახლის პირადი შინაარსი", news.findById(row.getId()).orElseThrow().getContent());
    }
}
