package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.SearchLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.search.GlobalSearchCache;
import ge.magti.portal.search.SearchQueryService;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final SearchQueryService searchQueryService;
    private final GlobalSearchCache globalSearchCache;
    private final SearchLogRepository searchLogRepository;
    private final ArticleTargetDepartmentRepository targetDepartmentRepository;
    private final CategoryRepository categoryRepository;

    public SearchController(
            SearchQueryService searchQueryService,
            GlobalSearchCache globalSearchCache,
            SearchLogRepository searchLogRepository,
            ArticleTargetDepartmentRepository targetDepartmentRepository,
            CategoryRepository categoryRepository) {
        this.searchQueryService = searchQueryService;
        this.globalSearchCache = globalSearchCache;
        this.searchLogRepository = searchLogRepository;
        this.targetDepartmentRepository = targetDepartmentRepository;
        this.categoryRepository = categoryRepository;
    }

    /** Port of global_search (routers/search.py:38-122). */
    @GetMapping("/api/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<Article> articles = searchQueryService.searchArticles(q, categoryId, user);
        writeSearchLog(user, q, articles.size());

        Set<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle = targetDepartmentRepository.findByArticleIdIn(articleIds).stream()
                .collect(Collectors.groupingBy(ArticleTargetDepartment::getArticleId,
                        Collectors.mapping(ArticleTargetDepartment::getDepartment, Collectors.toList())));

        List<ArticleResponse> response = articles.stream()
                .map(a -> ArticleResponse.from(a, deptsByArticle.getOrDefault(a.getId(), List.of())))
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Port of global_search_all (routers/search.py:204-256), incl. the 60s TTL cache + single-flight. */
    @GetMapping("/api/search/global")
    public ResponseEntity<?> searchGlobal(@RequestParam String q, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        String cacheKey = "search:" + q + ":" + user.getRole().name() + ":" + user.getDepartment();
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
        Map<Long, List<String>> deptsByArticle = targetDepartmentRepository.findByArticleIdIn(articleIds).stream()
                .collect(Collectors.groupingBy(ArticleTargetDepartment::getArticleId,
                        Collectors.mapping(ArticleTargetDepartment::getDepartment, Collectors.toList())));

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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<SearchLog> logs = searchLogRepository.findByUserIdOrderByTimestampDesc(
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
     * Mirrors both endpoints' identical logging condition (routers/search.py:110-120,
     * 232-239, 249-255): only for a non-empty, 3+ character, normalized query
     * term, and never for a {@code test_operator_} QA account.
     */
    private void writeSearchLog(User user, String q, int resultsFound) {
        String normalized = q == null ? "" : q.strip().toLowerCase();
        if (resultsFound <= 0 || normalized.length() < 3 || user.getEmail().startsWith("test_operator_")) {
            return;
        }
        SearchLog log = new SearchLog();
        log.setUserId(user.getId());
        log.setSearchTerm(normalized);
        log.setTimestamp(TbilisiTime.now());
        log.setHasResults(true);
        log.setResultsFound(resultsFound);
        searchLogRepository.save(log);
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }
}
