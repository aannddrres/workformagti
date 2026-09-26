package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.SearchLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.search.SearchReindexService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as every other controller integration test in this port.
 * Fixtures are persisted directly via repositories (then explicitly
 * reindexed via {@link SearchReindexService}, exactly what the real write
 * endpoints do) except where a test's own point is proving those write
 * endpoints keep the index in sync themselves.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchControllerIntegrationTest {

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
    private NewsRepository newsRepository;
    @Autowired
    private VideoInstructionRepository videoRepository;
    @Autowired
    private SearchLogRepository searchLogRepository;
    @Autowired
    private SearchReindexService searchReindexService;
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
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Category createCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private Article createArticle(String title, String content, String tags, String status, boolean isDraft,
            List<String> targetDepartments, OffsetDateTime publishedAt) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setTags(tags);
        article.setStatus(status);
        article.setDraft(isDraft);
        article.setPublishedAt(publishedAt);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        Article saved = articleRepository.saveAndFlush(article);
        for (String department : targetDepartments) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(saved.getId());
            row.setDepartment(department);
            targetDepartmentRepository.save(row);
        }
        searchReindexService.reindexArticle(saved);
        return saved;
    }

    private News createNews(String title, String content, String department, boolean isDraft, OffsetDateTime expiresAt) {
        News news = new News();
        news.setTitle(title);
        news.setContent(content);
        news.setTargetDepartment(department);
        news.setDraft(isDraft);
        news.setExpiresAt(expiresAt);
        news.setCreatedAt(TbilisiTime.now());
        News saved = newsRepository.saveAndFlush(news);
        searchReindexService.reindexNews(saved);
        return saved;
    }

    private VideoInstruction createVideo(String title, String category, String department, boolean archived) {
        VideoInstruction video = new VideoInstruction();
        video.setTitle(title);
        video.setCategory(category);
        video.setTargetDepartment(department);
        video.setArchived(archived);
        video.setVideoUrl("https://youtu.be/test");
        video.setCreatedAt(TbilisiTime.now());
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        searchReindexService.reindexVideo(saved);
        return saved;
    }

    @Test
    void noTokenIsUnauthorizedForAllThreeEndpoints() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "test")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/search/global").param("q", "test")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/search/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void searchRejectsOversizedQueriesBeforeDatabaseOrCacheWork() throws Exception {
        User operator = createUser("search-query-bounds@magti.ge", Role.OPERATOR, "All");
        String oversized = "x".repeat(201);

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("საძიებო ტექსტი არ უნდა აღემატებოდეს 200 სიმბოლოს"));
        mockMvc.perform(authed(get("/api/search/global"), tokenFor(operator)).param("q", oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("საძიებო ტექსტი არ უნდა აღემატებოდეს 200 სიმბოლოს"));
    }

    /**
     * The core value of the trigram index: a genuine mid-word substring
     * match (not a prefix), matching ILIKE '%word%' semantics, plus the
     * exact TITLE(10) &gt; TAGS(5) &gt; CONTENT(1) score precedence.
     */
    @Test
    void articleSearchFindsGeorgianMidWordSubstringAndRanksTitleAboveContent() throws Exception {
        // Scoped to a fresh category -- "კონფიგ" ("config") is a common enough
        // substring to now also match some of the 112 real imported tech
        // articles on this shared Oracle instance, same fix as
        // categoryIdNarrowsArticleSearchToThatCategoryOnly below.
        User admin = createUser("search-admin1@magti.ge", Role.CONTENT_ADMIN, "All");
        Category category = createCategory("კონფიგ-კატეგორია");
        Article titleMatch = createArticle("ქსელის დაკონფიგურირება", "შინაარსი", null,
                "published", false, List.of("All"), TbilisiTime.now());
        titleMatch.setCategoryId(category.getId());
        articleRepository.saveAndFlush(titleMatch);
        searchReindexService.reindexArticle(titleMatch);
        Article contentMatch = createArticle("სხვა თემა", "დეტალები კონფიგურაციის შესახებ", null,
                "published", false, List.of("All"), TbilisiTime.now());
        contentMatch.setCategoryId(category.getId());
        articleRepository.saveAndFlush(contentMatch);
        searchReindexService.reindexArticle(contentMatch);

        mockMvc.perform(authed(get("/api/search"), tokenFor(admin))
                        .param("q", "კონფიგ").param("category_id", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(titleMatch.getId().intValue()))
                .andExpect(jsonPath("$[1].id").value(contentMatch.getId().intValue()));
    }

    /** Proves the write-side reindex hooks (task: wire into controllers), not just the read side. */
    @Test
    void createUpdateAndDeleteViaRealEndpointsKeepTheIndexInSync() throws Exception {
        User admin = createUser("search-admin2@magti.ge", Role.CONTENT_ADMIN, "All");
        Category category = createCategory("რეინდექს-კატეგორია");

        String body = mockMvc.perform(authed(post("/api/articles"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ალფავერსია გამოცემა\",\"content\":\"დეტალები\",\"category_id\":"
                                + category.getId() + ",\"target_departments\":[\"All\"],\"status\":\"published\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(authed(get("/api/search"), tokenFor(admin)).param("q", "ალფავერსია"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(authed(put("/api/articles/" + id), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ბეტავერსია გამოცემა\",\"content\":\"დეტალები\",\"category_id\":"
                                + category.getId() + ",\"target_departments\":[\"All\"],\"status\":\"published\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/search"), tokenFor(admin)).param("q", "ალფავერსია"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(authed(get("/api/search"), tokenFor(admin)).param("q", "ბეტავერსია"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(authed(post("/api/articles/" + id + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(delete("/api/articles/" + id), tokenFor(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/search"), tokenFor(admin)).param("q", "ბეტავერსია"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * PO-34: a private draft belongs to its author, administrators included.
     * This test used to assert that any administrator found it -- in full,
     * since search answers with ArticleResponse.
     */
    @Test
    void onlyItsAuthorFindsAPrivateDraftArticleInSearch() throws Exception {
        // Scoped to a fresh category -- "დამალული" ("hidden") is a common
        // enough word to also match real imported content on this shared
        // Oracle instance.
        User operator = createUser("search-op1@magti.ge", Role.OPERATOR, "All");
        User admin = createUser("search-admin3@magti.ge", Role.CONTENT_ADMIN, "All");
        User otherAdmin = createUser("search-admin4@magti.ge", Role.CONTENT_ADMIN, "All");
        Category category = createCategory("დამალული-კატეგორია");
        Article draft = createArticle("დამალული დრაფტი", "შინაარსი", null, "draft", true, List.of("All"), null);
        draft.setCategoryId(category.getId());
        draft.setAuthorId(admin.getId());
        articleRepository.saveAndFlush(draft);
        searchReindexService.reindexArticle(draft);

        for (User notTheAuthor : List.of(operator, otherAdmin)) {
            mockMvc.perform(authed(get("/api/search"), tokenFor(notTheAuthor))
                            .param("q", "დამალული").param("category_id", category.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
        mockMvc.perform(authed(get("/api/search"), tokenFor(admin))
                        .param("q", "დამალული").param("category_id", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    /**
     * The department-scoping decision confirmed with the user: Search must
     * use the same prefix-aware rule as Article list/detail visibility, not
     * Python's original exact-match -- a sub-group operator must find
     * content targeted at their parent department.
     */
    @Test
    void subGroupOperatorFindsAnArticleTargetedOnlyAtTheParentDepartment() throws Exception {
        User operator = createUser("search-op2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        createArticle("პრეფიქსტესტი სტატია", "შინაარსი", null, "published", false,
                List.of("ტექნიკური"), TbilisiTime.now());

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", "პრეფიქსტესტი"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void categoryIdNarrowsArticleSearchToThatCategoryOnly() throws Exception {
        User admin = createUser("search-admin4@magti.ge", Role.CONTENT_ADMIN, "All");
        Category category = createCategory("ვიწრო-კატეგორია");
        Article inCategory = createArticle("ორივეში საერთო სიტყვა ერთი", "x", null,
                "published", false, List.of("All"), TbilisiTime.now());
        createArticle("ორივეში საერთო სიტყვა ორი", "x", null, "published", false, List.of("All"), TbilisiTime.now());
        inCategory.setCategoryId(category.getId());
        articleRepository.saveAndFlush(inCategory);
        searchReindexService.reindexArticle(inCategory);

        mockMvc.perform(authed(get("/api/search"), tokenFor(admin))
                        .param("q", "საერთო").param("category_id", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(inCategory.getId().intValue()));
    }

    @Test
    void globalSearchCombinesArticlesNewsAndVideosForTheSameQuery() throws Exception {
        // /api/search/global has no category_id filter (it spans 3 content
        // types), so isolation here comes from a unique term instead --
        // "პორტალის" ("portal's") now also matches real imported articles
        // on this shared Oracle instance.
        User operator = createUser("search-op3@magti.ge", Role.OPERATOR, "All");
        String uniqueTerm = "პორტალისუნიკალური" + System.nanoTime();
        createArticle(uniqueTerm + " სტატია", "x", null, "published", false, List.of("All"), TbilisiTime.now());
        createNews(uniqueTerm + " სიახლე", "x", "All", false, null);
        createVideo(uniqueTerm + " ვიდეო", "ზოგადი", "All", false);

        mockMvc.perform(authed(get("/api/search/global"), tokenFor(operator)).param("q", uniqueTerm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles", hasSize(1)))
                .andExpect(jsonPath("$.news", hasSize(1)))
                .andExpect(jsonPath("$.videos", hasSize(1)));
    }

    /**
     * The confirmed News fix: Python's own {@code _run_global_search_sync}
     * has no draft check at all for news (only department) -- a real
     * confidentiality gap presented to and fixed per the user's decision.
     */
    @Test
    void globalSearchShowsDraftNewsToItsAuthorOnly() throws Exception {
        User operator = createUser("search-op4@magti.ge", Role.OPERATOR, "All");
        User admin = createUser("search-admin5@magti.ge", Role.CONTENT_ADMIN, "All");
        User otherAdmin = createUser("search-admin6@magti.ge", Role.CONTENT_ADMIN, "All");
        News draft = createNews("დრაფტნიუსი გამოცემა", "x", "All", true, null);
        // NewsVisibility, as GET /api/news/{id} applies it: another
        // administrator gets a 404 there, so search does not name it either.
        draft.setAuthorId(admin.getId());
        newsRepository.saveAndFlush(draft);

        // The author first: global search caches its answer for 60 s, and a
        // key shared by role and department handed the author's draft to the
        // next administrator of the same department.
        mockMvc.perform(authed(get("/api/search/global"), tokenFor(admin)).param("q", "დრაფტნიუსი"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news", hasSize(1)));
        for (User notTheAuthor : List.of(operator, otherAdmin)) {
            mockMvc.perform(authed(get("/api/search/global"), tokenFor(notTheAuthor)).param("q", "დრაფტნიუსი"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.news", hasSize(0)));
        }
    }

    @Test
    void searchHistoryReturnsRecentSearchesMostRecentFirstAndOnlyForTheCaller() throws Exception {
        User operator = createUser("search-op5@magti.ge", Role.OPERATOR, "All");
        User otherOperator = createUser("search-op5-other@magti.ge", Role.OPERATOR, "All");
        createArticle("პირველი საძიებო სიტყვა", "x", null, "published", false, List.of("All"), TbilisiTime.now());
        createArticle("მეორე საძიებო სიტყვა", "x", null, "published", false, List.of("All"), TbilisiTime.now());

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", "პირველი"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", "მეორე"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/search/history"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].search_term").value("მეორე"))
                .andExpect(jsonPath("$[1].search_term").value("პირველი"));

        mockMvc.perform(authed(get("/api/search/history"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void queriesShorterThanThreeCharactersAreNeverLogged() throws Exception {
        // Scoped to a fresh category -- a bare 2-char query ("ab") falls back
        // to scanning every article on this shared Oracle instance, and now
        // collides with Latin substrings inside the 112 real imported
        // articles (tech terms, URLs, etc). category_id filtering still
        // applies after the scan (SearchQueryService.searchArticles), so
        // narrowing to this test's own category isolates it correctly.
        User operator = createUser("search-op6@magti.ge", Role.OPERATOR, "All");
        Category category = createCategory("ab-კატეგორია");
        Article article = createArticle("ab თემა", "x", null, "published", false, List.of("All"), TbilisiTime.now());
        article.setCategoryId(category.getId());
        articleRepository.saveAndFlush(article);
        searchReindexService.reindexArticle(article);

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator))
                        .param("q", "ab").param("category_id", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        List<SearchLog> logs = searchLogRepository.findAll().stream()
                .filter(l -> l.getUserId().equals(operator.getId())).toList();
        assertEquals(0, logs.size());
    }

    /**
     * The admin stats page has a "failed searches" panel, and until this test
     * existed nothing could ever appear in it: writeSearchLog returned early on
     * a zero-result query, so no {@code has_results = false} row was ever
     * written by either endpoint.
     *
     * <p>Asserted at the repository, not through
     * {@code /api/statistics/failed-searches}: that endpoint returns the top 10
     * terms by count across the whole instance, so a fixture with a count of
     * one is not guaranteed a place in it. That the endpoint surfaces such rows
     * once they exist is already covered, by
     * StatsControllerIntegrationTest#popularAndFailedSearchesGroupNormalizedTermsCaseAndWhitespace
     * -- which seeds its failed rows directly, and is precisely why the missing
     * write side went unnoticed for so long.
     */
    @Test
    void aSearchThatFindsNothingIsRecordedAsAFailedSearch() throws Exception {
        User operator = createUser("search-op7@magti.ge", Role.OPERATOR, "All");
        // nanoTime so it cannot collide with the 112 real imported articles,
        // and so the 60s global-search cache cannot answer from another run.
        String missing = "არარსებული-მოთხოვნა-" + System.nanoTime();

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", missing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(authed(get("/api/search/global"), tokenFor(operator)).param("q", missing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles", hasSize(0)))
                .andExpect(jsonPath("$.news", hasSize(0)))
                .andExpect(jsonPath("$.videos", hasSize(0)));

        List<SearchLog> logs = searchLogRepository.findAll().stream()
                .filter(l -> l.getUserId().equals(operator.getId())).toList();
        assertEquals(2, logs.size(), "a search that found nothing was not recorded at all");
        for (SearchLog log : logs) {
            assertEquals(missing.toLowerCase(), log.getSearchTerm());
            assertFalse(log.isHasResults(), "a miss was recorded as a hit, so it can never reach the failed panel");
            assertEquals(0, log.getResultsFound().intValue());
        }
    }

    /** The other half of the same flag: a hit is still a hit. */
    @Test
    void aSearchThatFindsSomethingIsStillRecordedAsSuccessful() throws Exception {
        User operator = createUser("search-op8@magti.ge", Role.OPERATOR, "All");
        String term = "ნაპოვნი-" + System.nanoTime();
        createArticle(term + " თემა", "შიგთავსი", null, "published", false, List.of("All"), TbilisiTime.now());

        mockMvc.perform(authed(get("/api/search"), tokenFor(operator)).param("q", term))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        List<SearchLog> logs = searchLogRepository.findAll().stream()
                .filter(l -> l.getUserId().equals(operator.getId())).toList();
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).isHasResults());
        assertEquals(1, logs.get(0).getResultsFound().intValue());
    }
}
