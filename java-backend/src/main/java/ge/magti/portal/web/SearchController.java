package ge.magti.portal.web;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.SearchLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.search.GlobalSearchCache;
import ge.magti.portal.search.SearchQueryService;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors routers/search.py's 3 endpoints: {@code /api/search} (KB article
 * search), {@code /api/search/global} (portal-wide, cached + coalesced),
 * {@code /api/search/history}. See {@link SearchQueryService} for the actual
 * query/scoring logic this controller only wires up and logs around.
 */
@RestController
public class SearchController {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final String QUERY_TOO_LONG_DETAIL = "საძიებო ტექსტი არ უნდა აღემატებოდეს 200 სიმბოლოს";

    private final SearchQueryService searchQueryService;
    private final GlobalSearchCache globalSearchCache;
    private final SearchLogRepository searchLogRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final CategoryRepository categoryRepository;

    public SearchController(
            SearchQueryService searchQueryService,
            GlobalSearchCache globalSearchCache,
            SearchLogRepository searchLogRepository,
            ArticleTargetQueryService articleTargetQueryService,
            CategoryRepository categoryRepository) {
        this.searchQueryService = searchQueryService;
        this.globalSearchCache = globalSearchCache;
        this.searchLogRepository = searchLogRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.categoryRepository = categoryRepository;
    }

    /** Port of global_search (routers/search.py:38-122). */
    @GetMapping("/api/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (queryTooLong(q)) {
            return ResponseEntity.badRequest().body(Map.of("detail", QUERY_TOO_LONG_DETAIL));
        }

        List<Article> articles = searchQueryService.searchArticles(q, categoryId, user);
        writeSearchLog(user, q, articles.size());

        Set<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle =
                articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);

        List<ArticleResponse> response = articles.stream()
                .map(a -> ArticleResponse.from(a, deptsByArticle.getOrDefault(a.getId(), List.of())))
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Port of global_search_all (routers/search.py:204-256), with its single-flight; no result is kept (GlobalSearchCache). */
    @GetMapping("/api/search/global")
    public ResponseEntity<?> searchGlobal(@RequestParam String q, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (queryTooLong(q)) {
            return ResponseEntity.badRequest().body(Map.of("detail", QUERY_TOO_LONG_DETAIL));
        }

        String normalizedQuery = q.strip().toLowerCase(Locale.ROOT);
        // Per person, not per role and department. An author's results carry
        // their own private drafts (ArticleVisibility, NewsVisibility), and a
        // key shared by role and department served them for the next 60 s to
        // every colleague with the same two (PO-34).
        String cacheKey = "search:" + normalizedQuery + ":" + user.getId() + ":" + user.getRole().name()
                + ":" + user.getDepartment();
        SearchQueryService.GlobalSearchResult result =
                globalSearchCache.getOrCompute(cacheKey, () -> searchQueryService.searchGlobal(q, user));

        int resultsFound = result.articles().size() + result.news().size() + result.videos().size();
        writeSearchLog(user, q, resultsFound);

        Map<Long, String> categoryNames = new HashMap<>();
        Set<Long> categoryIds = result.articles().stream()
                .map(Article::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!categoryIds.isEmpty()) {
            categoryRepository.findAllById(categoryIds).forEach(c -> categoryNames.put(c.getId(), c.getName()));
        }
        Set<Long> articleIds = result.articles().stream().map(Article::getId).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle =
                articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);

        List<ArticleSummaryResponse> articles = result.articles().stream()
                .map(a -> ArticleSummaryResponse.from(
                        a, categoryNames.get(a.getCategoryId()), deptsByArticle.getOrDefault(a.getId(), List.of())))
                .toList();
        List<NewsSummaryResponse> news = result.news().stream().map(NewsSummaryResponse::from).toList();
        List<VideoInstructionResponse> videos = result.videos().stream().map(VideoInstructionResponse::from).toList();

        return ResponseEntity.ok(new GlobalSearchResponse(articles, news, videos));
    }

    /** Port of get_search_history (routers/search.py:258-285). */
    @GetMapping("/api/search/history")
    public ResponseEntity<?> searchHistory(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<SearchLog> logs = searchLogRepository.findByUserIdOrderByTimestampDescIdDesc(
                user.getId(), PageRequest.of(0, 50));
        List<Map<String, Object>> response = logs.stream()
                .map(log -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", log.getId());
                    row.put("search_term", log.getSearchTerm());
                    row.put("timestamp", log.getTimestamp());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Records the query behind both search analytics panels on the admin stats
     * page: popular searches ({@code has_results = true}) and failed searches
     * ({@code has_results = false}).
     *
     * <p>A search that found NOTHING is written too, and that is the entire
     * point of the second panel: what an operator looked for and could not find
     * is the one signal that says which article the knowledge base is missing.
     * This used to skip on {@code resultsFound <= 0} and then hardcode
     * {@code setHasResults(true)}, so no row with {@code has_results = false}
     * could ever exist and {@code SearchLogRepository.failedSearchTerms()} was
     * structurally guaranteed to return an empty list. The panel rendered its
     * "nothing here" empty state permanently -- which reads as good news, which
     * is why it went unnoticed.
     *
     * <p>Only the length and QA-account guards are conditions on writing at
     * all; the result count decides the FLAG, never whether there is a row.
     *
     * <p>Divergence from Python, deliberate: routers/search.py logged misses on
     * {@code /api/search/global} (search.py:232-239, 249-255) but not on
     * {@code /api/search} (search.py:113, which required {@code len(articles) >
     * 0}). Both are a person failing to find something, so both are recorded
     * here. The knowledge-base page's own search box is the one an operator
     * uses most, and dropping its misses would leave the panel half-blind.
     */
    private void writeSearchLog(User user, String q, int resultsFound) {
        String normalized = q == null ? "" : q.strip().toLowerCase();
        if (normalized.length() < 3 || user.getEmail().startsWith("test_operator_")) {
            return;
        }
        SearchLog log = new SearchLog();
        log.setUserId(user.getId());
        log.setSearchTerm(normalized);
        log.setTimestamp(TbilisiTime.now());
        log.setHasResults(resultsFound > 0);
        log.setResultsFound(resultsFound);
        searchLogRepository.save(log);
    }

    private static boolean queryTooLong(String q) {
        return q != null && q.strip().length() > MAX_QUERY_LENGTH;
    }

}
