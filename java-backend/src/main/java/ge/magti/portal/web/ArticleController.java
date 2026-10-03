package ge.magti.portal.web;

import ge.magti.portal.article.ArticleListFilter;
import ge.magti.portal.article.ArticleListItem;
import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.article.ArticleReferenceItem;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.article.ArticleQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleViewLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.util.DepartmentMatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;
import static ge.magti.portal.web.ArticleEndpointSupport.assertArticleVisible;

/**
 * Reading articles: the list, one article, an article's related articles and
 * the caller's recently viewed list.
 *
 * <p>The article API is split by responsibility across these controllers.
 * Every route, method, parameter, guard and response is what it was when they
 * were one class:
 * <ul>
 *   <li>{@link ArticleController} -- reading (this class);
 *   <li>{@link ArticleEditController} -- create, update and autosave;
 *   <li>{@link ArticleLifecycleController} -- delete, archive, unarchive and
 *       the bulk operations;
 *   <li>{@link ArticleHistoryController} -- history, versions, diff and
 *       restore;
 *   <li>{@link ArticleReadTrackingController} -- read receipts and view
 *       tracking;
 *   <li>{@link ArticleNoteController} -- a reader's private note;
 *   <li>{@link ArticleVerificationController} -- verify and the stale report;
 *   <li>{@link ArticleCommandController} -- create or update together with
 *       mandatory reading and the quiz, in one transaction.
 * </ul>
 * What more than one of them needs -- the not-found answer, the visibility
 * check, the audience lookup and the two article permission guards -- is in
 * {@link ArticleEndpointSupport}, once.
 *
 * <p>Same two-gate shape as {@link VideoController}/{@link
 * CategoryController}: no/invalid token (401, English), wrong role for
 * create/update/autosave/delete (403, English -- {@code
 * get_current_admin_user}), missing the granular {@code articles.archive}
 * permission for archive/unarchive/bulk-archive (403, Georgian -- {@code
 * require_permission}).
 *
 * <p><b>Bug #314 fix, user-confirmed 2026-08-13:</b> create/update/autosave/
 * delete now require {@code articles.edit}, including direct publishing,
 * on top of the role gate -- see {@link
 * ArticleEndpointSupport#requireArticlesEditPermission}'s javadoc for why.
 * Deliberately NOT extended to {@code articles.view}: that would mean
 * threading a permission check through {@code assertArticleVisible}, reused
 * by every note/quiz child-route in these controllers (a much larger,
 * harder-to-verify surface than the 4 mutating endpoints this fix actually
 * targets) -- left as a known, documented remaining gap rather than widened
 * opportunistically.
 *
 * <p>Create/update/archive/unarchive/version-restore now write reconstructable
 * audit evidence in the same transaction as the content, target-department,
 * tag, search-index and history changes. Delete delegates the same fail-closed
 * rule to {@link ContentLifecycleService}. There is no TTL cache clearing
 * (search_cache/category_cache); no SSE broadcast (_notify/_notify_revision)
 * -- none of that infrastructure exists in the Java port yet.
 */
@RestController
public class ArticleController {

    private final ArticleRepository articleRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final ArticleViewLogRepository articleViewLogRepository;
    private final CategoryRepository categoryRepository;
    private final ArticleQueryService articleQueryService;
    private final ArticleEndpointSupport articleSupport;

    public ArticleController(
            ArticleRepository articleRepository,
            ArticleTargetQueryService articleTargetQueryService,
            ArticleViewLogRepository articleViewLogRepository,
            CategoryRepository categoryRepository,
            ArticleQueryService articleQueryService,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.articleViewLogRepository = articleViewLogRepository;
        this.categoryRepository = categoryRepository;
        this.articleQueryService = articleQueryService;
        this.articleSupport = articleSupport;
    }

    @GetMapping("/api/articles")
    public ResponseEntity<?> getArticles(
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (ListQueryBounds.isInvalid(skip, limit)) {
            return ResponseEntity.badRequest().body(Map.of("detail", ListQueryBounds.INVALID_DETAIL));
        }

        List<ArticleListItem> articles = articleQueryService.listVisible(
                new ArticleListFilter(q, categoryId, status), user, skip, limit);

        Set<Long> categoryIds = articles.stream()
                .map(ArticleListItem::categoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        Set<Long> articleIds = articles.stream().map(ArticleListItem::id).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle =
                articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);

        List<ArticleSummaryResponse> result = articles.stream()
                .map(a -> ArticleSummaryResponse.from(
                        a, categoryNames.get(a.categoryId()), deptsByArticle.getOrDefault(a.id(), List.of())))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/articles/{id}")
    public ResponseEntity<?> getArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> targetDepartments = articleSupport.resolveTargetDepartments(id);

        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, targetDepartments, user);
        if (visibility != null) {
            return visibility;
        }

        return ResponseEntity.ok(ArticleResponse.from(article, targetDepartments));
    }

    @GetMapping("/api/articles/{id}/related")
    public ResponseEntity<?> getRelatedArticles(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        // A missing source article returns an empty list, not a 404.
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        Article source = found.get();
        ResponseEntity<Map<String, String>> visibility =
                assertArticleVisible(source, articleSupport.resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        List<ArticleReferenceItem> published = CompleteResultGuard.enforce(
                        articleRepository.findReferencesByStatus(
                                "published", user.getId(), CompleteResultGuard.sentinelPage())).stream()
                .filter(a -> !a.id().equals(id))
                .toList();
        Set<Long> candidateIds = published.stream().map(ArticleReferenceItem::id).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle =
                articleTargetQueryService.targetDepartmentsByArticleWithinLimit(candidateIds);
        boolean isAdmin = user.seesAllContent();
        // Deliberately exact-match + "All" only, NOT DepartmentMatcher's
        // prefix-aware rule -- this one candidate filter is narrower than
        // the article list's own query, and that difference is carried
        // forward unchanged rather than unified.
        List<ArticleReferenceItem> candidates = published.stream()
                .filter(a -> isAdmin
                        || relatedArticleDeptMatches(user.getDepartment(), deptsByArticle.getOrDefault(a.id(), List.of())))
                .toList();

        List<ArticleReferenceItem> results = new ArrayList<>(candidates.stream()
                .filter(a -> Objects.equals(a.categoryId(), source.getCategoryId()))
                .limit(4)
                .toList());

        if (results.size() < 4 && source.getTags() != null && !source.getTags().isBlank()) {
            Set<Long> existingIds = new LinkedHashSet<>();
            results.forEach(a -> existingIds.add(a.id()));
            List<String> tagList = Arrays.stream(source.getTags().split(","))
                    .map(t -> t.strip().toLowerCase())
                    .filter(t -> !t.isEmpty())
                    .toList();
            for (String tag : tagList) {
                if (results.size() >= 4) {
                    break;
                }
                int remaining = 4 - results.size();
                List<ArticleReferenceItem> tagMatches = candidates.stream()
                        .filter(a -> !existingIds.contains(a.id()))
                        .filter(a -> a.tags() != null && a.tags().toLowerCase().contains(tag))
                        .limit(remaining)
                        .toList();
                for (ArticleReferenceItem a : tagMatches) {
                    if (existingIds.add(a.id())) {
                        results.add(a);
                    }
                }
            }
        }

        if (results.size() < 4) {
            Set<Long> existingIds = new LinkedHashSet<>();
            results.forEach(a -> existingIds.add(a.id()));
            int remaining = 4 - results.size();
            List<ArticleReferenceItem> fillMatches = candidates.stream()
                    .filter(a -> !existingIds.contains(a.id()))
                    // Newest first, undated last. created_at is nullable (V4) and
                    // only the create endpoint fills it; one row written any other
                    // way made this sort throw, and every reader's list a 500.
                    .sorted(Comparator.comparing(ArticleReferenceItem::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(remaining)
                    .toList();
            for (ArticleReferenceItem a : fillMatches) {
                if (existingIds.add(a.id())) {
                    results.add(a);
                }
            }
        }

        List<RelatedArticleResponse> response = results.stream()
                .limit(4)
                .map(a -> new RelatedArticleResponse(a.id(), a.title(), a.categoryId(), a.tags()))
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/me/recently-viewed")
    public ResponseEntity<?> getMyRecentlyViewed(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<ArticleViewLog> rows = articleViewLogRepository.findTop30ByOperatorIdOrderByViewedAtDesc(user.getId());
        Set<Long> articleIds = rows.stream().map(ArticleViewLog::getArticleId).filter(Objects::nonNull).collect(Collectors.toSet());
        // The whole reading rule, not only the private-draft half: a title the
        // reader may no longer open -- retargeted away, archived, unpublished
        // -- stayed listed here, live and renamed (simulation, 2026-10-01).
        Map<Long, List<String>> audiences = articleIds.isEmpty() ? Map.of()
                : articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);
        Map<Long, String> titlesByArticleId = articleRepository.findAllById(articleIds).stream()
                .filter(a -> ArticleVisibility.isVisible(a, audiences.getOrDefault(a.getId(), List.of()), user))
                .collect(Collectors.toMap(Article::getId, Article::getTitle));

        Set<Long> seenIds = new LinkedHashSet<>();
        List<RecentlyViewedItemResponse> items = new ArrayList<>();
        for (ArticleViewLog row : rows) {
            Long articleId = row.getArticleId();
            // INNER JOIN semantics: a view of a
            // since-deleted article (article_id SET NULL on delete) never
            // resolves a title, so it's silently skipped, not shown blank.
            if (articleId == null || !titlesByArticleId.containsKey(articleId) || seenIds.contains(articleId)) {
                continue;
            }
            seenIds.add(articleId);
            items.add(new RecentlyViewedItemResponse(articleId, titlesByArticleId.get(articleId), row.getViewedAt()));
            if (items.size() >= 10) {
                break;
            }
        }
        return ResponseEntity.ok(items);
    }

    /**
     * The related-articles endpoint's narrower department filter
     * -- exact match or "All" only, no
     * {@link DepartmentMatcher} prefix support. Deliberately not reusing
     * {@code assertArticleVisible}'s dept check, which calls
     * DepartmentMatcher.matches.
     */
    private static boolean relatedArticleDeptMatches(String userDepartment, List<String> targets) {
        for (String target : targets) {
            if ("All".equals(target) || Objects.equals(target, userDepartment)) {
                return true;
            }
        }
        return false;
    }
}
