package ge.magti.portal.web;

import ge.magti.portal.article.ArticleEvidenceCardinalityGuard;
import ge.magti.portal.article.ArticleHistorySummary;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ArticleHtmlSanitizer;
import ge.magti.portal.diff.DiffResult;
import ge.magti.portal.diff.HtmlDiffer;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.User;
import ge.magti.portal.history.HistoryPayloadGuard;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;
import static ge.magti.portal.web.ArticleEndpointSupport.notFoundMap;
import static ge.magti.portal.web.ArticleEndpointSupport.assertArticleVisible;

/**
 * An article's revision history: the history lists, one snapshot, the diff
 * between versions, the version list and restoring an old version. Part of
 * the article API split described on {@link ArticleController}.
 */
@RestController
public class ArticleHistoryController {

    private final ArticleRepository articleRepository;
    private final ArticleHistoryRepository articleHistoryRepository;
    private final UserRepository userRepository;
    private final PermissionChecker permissionChecker;
    private final SearchReindexService searchReindexService;
    private final ArticleHtmlSanitizer articleHtmlSanitizer;
    private final MutationAuditService contentMutationAuditService;
    private final FileReferenceIndex fileReferenceIndex;
    private final ArticleEndpointSupport articleSupport;

    public ArticleHistoryController(
            ArticleRepository articleRepository,
            ArticleHistoryRepository articleHistoryRepository,
            UserRepository userRepository,
            PermissionChecker permissionChecker,
            SearchReindexService searchReindexService,
            ArticleHtmlSanitizer articleHtmlSanitizer,
            MutationAuditService contentMutationAuditService,
            FileReferenceIndex fileReferenceIndex,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.articleHistoryRepository = articleHistoryRepository;
        this.userRepository = userRepository;
        this.permissionChecker = permissionChecker;
        this.searchReindexService = searchReindexService;
        this.articleHtmlSanitizer = articleHtmlSanitizer;
        this.contentMutationAuditService = contentMutationAuditService;
        this.fileReferenceIndex = fileReferenceIndex;
        this.articleSupport = articleSupport;
    }

    @GetMapping("/api/articles/{id}/history")
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> getArticleHistory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        // No existence check here: a missing article_id isn't checked
        // separately, it just yields zero
        // matching history rows -- an empty list, not a 404.
        ResponseEntity<Map<String, String>> visibility = denyInvisibleExistingArticle(id, user);
        if (visibility != null) {
            return visibility;
        }
        HistoryPayloadGuard.enforceFullResponseCharacters(
                articleHistoryRepository.totalContentCharactersByArticleId(id));
        List<ArticleHistory> history = ArticleEvidenceCardinalityGuard.enforceWithinLimit(
                articleHistoryRepository.findByArticleIdOrderByUpdatedAtDesc(
                        id, PageRequest.of(0, ArticleEvidenceCardinalityGuard.MAX_ROWS + 1)));
        Set<Long> authorIds = history.stream().map(ArticleHistory::getUpdatedBy).collect(Collectors.toSet());
        Map<Long, String> namesByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        // INNER JOIN semantics:
        // a history row whose updated_by no longer matches any user is
        // silently dropped, not shown with a null author.
        List<ArticleHistoryItemResponse> response = history.stream()
                .filter(h -> namesByUserId.containsKey(h.getUpdatedBy()))
                .map(h -> new ArticleHistoryItemResponse(
                        h.getId(), h.getTitle(), h.getContent(), h.getUpdatedAt(),
                        namesByUserId.get(h.getUpdatedBy()), h.getVersionId()))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Backward-compatible CLOB-free history list for list-first clients.
     * The legacy {@code /history} response remains unchanged; first-party UI
     * uses this endpoint and fetches one full snapshot only on expansion.
     */
    @GetMapping("/api/articles/{id}/history-summary")
    public ResponseEntity<?> getArticleHistorySummary(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        ResponseEntity<Map<String, String>> visibility = denyInvisibleExistingArticle(id, user);
        if (visibility != null) {
            return visibility;
        }
        List<ArticleHistorySummary> history = ArticleEvidenceCardinalityGuard.enforceWithinLimit(
                articleHistoryRepository.findSummaryByArticleIdOrderByUpdatedAtDesc(
                        id, PageRequest.of(0, ArticleEvidenceCardinalityGuard.MAX_ROWS + 1)));
        Set<Long> authorIds = history.stream().map(ArticleHistorySummary::updatedBy).collect(Collectors.toSet());
        Map<Long, String> namesByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        List<ArticleHistorySummaryResponse> response = history.stream()
                .filter(h -> namesByUserId.containsKey(h.updatedBy()))
                .map(h -> new ArticleHistorySummaryResponse(
                        h.id(), h.title(), h.updatedAt(), namesByUserId.get(h.updatedBy()), h.versionId()))
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Loads one CLOB snapshot after an admin selects a summary row. */
    @GetMapping("/api/articles/{id}/history/{historyId}")
    public ResponseEntity<?> getArticleHistoryItem(
            @PathVariable Long id, @PathVariable Long historyId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        ResponseEntity<Map<String, String>> visibility = denyInvisibleExistingArticle(id, user);
        if (visibility != null) {
            return visibility;
        }
        Optional<ArticleHistory> history = articleHistoryRepository.findByIdAndArticleId(historyId, id);
        if (history.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        ArticleHistory row = history.get();
        Optional<User> author = userRepository.findById(row.getUpdatedBy());
        if (author.isEmpty()) {
            // Preserve the legacy list's INNER JOIN semantics.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        return ResponseEntity.ok(new ArticleHistoryItemResponse(
                row.getId(), row.getTitle(), row.getContent(), row.getUpdatedAt(),
                author.get().getName(), row.getVersionId()));
    }

    @GetMapping("/api/articles/{id}/history/{historyId}/diff")
    public ResponseEntity<?> getArticleDiff(
            @PathVariable Long id, @PathVariable Long historyId,
            @RequestParam(name = "compare_history_id", required = false) Long compareHistoryId,
            @RequestParam(name = "compare_to_predecessor", defaultValue = "false") boolean compareToPredecessor,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, articleSupport.resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        Optional<ArticleHistory> snapOpt = articleHistoryRepository.findByIdAndArticleId(historyId, id);
        if (snapOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        ArticleHistory snap = snapOpt.get();

        String otherContent;
        int otherVersion;
        if (compareToPredecessor) {
            // No predecessor (version 1) -> self-compare against its own
            // content, which shows cleanly with no diffs rather than erroring.
            Optional<ArticleHistory> predecessor = articleHistoryRepository
                    .findFirstByArticleIdAndVersionIdLessThanOrderByVersionIdDesc(id, snap.getVersionId());
            otherContent = predecessor.map(ArticleHistory::getContent).orElse(snap.getContent());
            otherVersion = predecessor.map(ArticleHistory::getVersionId).orElse(snap.getVersionId());
        } else if (compareHistoryId != null) {
            Optional<ArticleHistory> compareSnap = articleHistoryRepository.findByIdAndArticleId(compareHistoryId, id);
            if (compareSnap.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "შესადარებელი ისტორიის ვერსია ვერ მოიძებნა"));
            }
            otherContent = compareSnap.get().getContent();
            otherVersion = compareSnap.get().getVersionId();
        } else {
            otherContent = article.getContent();
            otherVersion = article.getVersion();
        }

        return ResponseEntity.ok(diffOrderedByVersion(snap.getContent(), snap.getVersionId(), otherContent, otherVersion, snap.getVersionId()));
    }

    @PostMapping("/api/articles/{id}/history/{historyId}/restore")
    @Transactional
    public ResponseEntity<?> restoreArticleVersion(
            @PathVariable Long id, @PathVariable Long historyId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        // A restore rewrites the text; a DENY on articles.edit has to reach it
        // too, or it left this way open (simulation, 2026-10-01).
        ResponseEntity<Map<String, String>> editDenial = articleSupport.requireArticlesEditPermission(user);
        if (editDenial != null) {
            return editDenial;
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
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);

        Optional<ArticleHistory> historyOpt = articleHistoryRepository.findByIdAndArticleId(historyId, id);
        if (historyOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        ArticleHistory history = historyOpt.get();

        articleHistoryRepository.archiveIfMissing(article.getId(), article.getTitle(), article.getContent(),
                user.getId(), article.getVersion(),
                article.getUpdatedAt() != null ? article.getUpdatedAt() : TbilisiTime.now());

        article.setTitle(history.getTitle());
        article.setContent(articleHtmlSanitizer.sanitize(history.getContent()));
        article.setVersion(article.getVersion() + 1);
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        searchReindexService.reindexArticle(saved);

        ArticleHistory restoredHistory = new ArticleHistory();
        restoredHistory.setArticleId(id);
        restoredHistory.setTitle(saved.getTitle());
        restoredHistory.setContent(saved.getContent());
        restoredHistory.setUpdatedBy(user.getId());
        restoredHistory.setVersionId(saved.getVersion());
        restoredHistory.setUpdatedAt(saved.getUpdatedAt());
        articleHistoryRepository.save(restoredHistory);

        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("article", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "RESTORE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, targetDepartments));

        return ResponseEntity.ok(ArticleResponse.from(saved, targetDepartments));
    }

    @GetMapping("/api/articles/{id}/versions")
    @Transactional
    public ResponseEntity<?> getArticleVersions(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, articleSupport.resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        // Self-healing: legacy articles
        // predating the Unified Revision Log feature may be missing a
        // history row for their current version -- ensure one exists
        // before listing, sharing the same race-safe archive logic as
        // update/restore instead of reimplementing it.
        Long fallbackAuthor = article.getAuthorId() != null ? article.getAuthorId() : user.getId();
        articleHistoryRepository.archiveIfMissing(article.getId(), article.getTitle(), article.getContent(),
                fallbackAuthor, article.getVersion(),
                article.getUpdatedAt() != null ? article.getUpdatedAt() : TbilisiTime.now());

        List<ArticleHistorySummary> history = ArticleEvidenceCardinalityGuard.enforceWithinLimit(
                articleHistoryRepository.findSummaryByArticleIdOrderByVersionIdDesc(
                        id, PageRequest.of(0, ArticleEvidenceCardinalityGuard.MAX_ROWS + 1)));
        Set<Long> authorIds = history.stream()
                .map(ArticleHistorySummary::updatedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> namesByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        // OUTER JOIN semantics, unlike
        // the history list's INNER JOIN above: a row survives even when
        // updated_by has no matching user, with author_name = null.
        List<ArticleVersionItemResponse> versions = history.stream()
                .map(h -> new ArticleVersionItemResponse(
                        h.versionId() != null ? h.versionId() : 0, h.title(), h.updatedAt(),
                        namesByUserId.get(h.updatedBy()), h.id()))
                .sorted(Comparator.comparingInt(ArticleVersionItemResponse::version).reversed())
                .toList();
        return ResponseEntity.ok(versions);
    }

    private static ArticleDiffResponse diffOrderedByVersion(
            String contentA, int versionA, String contentB, int versionB, int snapVersionId) {
        DiffResult result;
        int baseVersion;
        int compareVersion;
        if (versionA < versionB) {
            result = HtmlDiffer.diffHtml(contentA, contentB);
            baseVersion = versionA;
            compareVersion = versionB;
        } else {
            result = HtmlDiffer.diffHtml(contentB, contentA);
            baseVersion = versionB;
            compareVersion = versionA;
        }
        return new ArticleDiffResponse(result.html(), result.added(), result.removed(), baseVersion, compareVersion, snapVersionId);
    }

    /** Preserve legacy missing-article history responses while hiding existing private drafts. */
    private ResponseEntity<Map<String, String>> denyInvisibleExistingArticle(Long articleId, User user) {
        // Before findById, which cannot see a trashed row: a private draft in
        // the trash is still its author's alone.
        if (articleRepository.countPrivateDraftOfAnotherIncludingTrash(articleId, user.getId()) > 0) {
            return notFoundMap();
        }
        return articleRepository.findById(articleId)
                .map(article -> assertArticleVisible(article, articleSupport.resolveTargetDepartments(articleId), user))
                .orElse(null);
    }
}
