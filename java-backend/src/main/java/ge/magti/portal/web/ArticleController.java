package ge.magti.portal.web;

import ge.magti.portal.article.ArticleListFilter;
import ge.magti.portal.article.ArticleListItem;
import ge.magti.portal.article.ArticleEvidenceCardinalityGuard;
import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.article.ArticleHistorySummary;
import ge.magti.portal.article.ArticleReferenceItem;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.content.ArticleHtmlSanitizer;
import ge.magti.portal.article.ArticleQueryService;
import ge.magti.portal.article.ArticleViewQueryService;
import ge.magti.portal.article.EligibleOperatorsService;
import ge.magti.portal.diff.DiffResult;
import ge.magti.portal.diff.HtmlDiffer;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.ArticleReadReceipt;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.ArticleViewLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserNote;
import ge.magti.portal.history.HistoryPayloadGuard;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserNoteRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.quiz.QuizGateChecker;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.security.Scope;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.TagSyncService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors routers/articles.py -- core CRUD + lifecycle only (list, get,
 * create, update, autosave, delete, archive, unarchive, bulk-archive). The
 * remaining 23 endpoints (history/diff/restore, quiz, knowledge-score/
 * leaderboard, read-receipts/views, notes/verify/stale/related) are later,
 * separate slices -- this domain is too large to port
 * in one HTTP surface the way Videos/Categories were.
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
 * #requireArticlesEditPermission}'s javadoc for why. Deliberately NOT
 * extended to {@code articles.view}: that would mean threading a permission
 * check through {@code assertArticleVisible}, reused by every note/quiz
 * child-route in this file (a much larger, harder-to-verify
 * surface than the 4 mutating endpoints this fix actually targets) --
 * left as a known, documented remaining gap rather than widened
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

    private static final String NOT_FOUND_DETAIL = "სტატია ვერ მოიძებნა";

    private final ArticleRepository articleRepository;
    private final ArticleTargetDepartmentRepository targetDepartmentRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final ArticleHistoryRepository articleHistoryRepository;
    private final ArticleReadReceiptRepository articleReadReceiptRepository;
    private final ArticleViewLogRepository articleViewLogRepository;
    private final CategoryRepository categoryRepository;
    private final UserNoteRepository userNoteRepository;
    private final UserRepository userRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final QuizGateChecker quizGateChecker;
    private final PermissionChecker permissionChecker;
    private final ScopeResolver scopeResolver;
    private final TagSyncService tagSyncService;
    private final ArticleQueryService articleQueryService;
    private final ArticleViewQueryService articleViewQueryService;
    private final EligibleOperatorsService eligibleOperatorsService;
    private final SearchReindexService searchReindexService;
    private final ContentLifecycleService contentLifecycleService;
    private final ArticleHtmlSanitizer articleHtmlSanitizer;
    private final MutationAuditService contentMutationAuditService;
    private final FileReferenceIndex fileReferenceIndex;

    public ArticleController(
            ArticleRepository articleRepository,
            ArticleTargetDepartmentRepository targetDepartmentRepository,
            ArticleTargetQueryService articleTargetQueryService,
            ArticleHistoryRepository articleHistoryRepository,
            ArticleReadReceiptRepository articleReadReceiptRepository,
            ArticleViewLogRepository articleViewLogRepository,
            CategoryRepository categoryRepository,
            UserNoteRepository userNoteRepository,
            UserRepository userRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            QuizGateChecker quizGateChecker,
            PermissionChecker permissionChecker,
            ScopeResolver scopeResolver,
            TagSyncService tagSyncService,
            ArticleQueryService articleQueryService,
            ArticleViewQueryService articleViewQueryService,
            EligibleOperatorsService eligibleOperatorsService,
            SearchReindexService searchReindexService,
            ContentLifecycleService contentLifecycleService,
            ArticleHtmlSanitizer articleHtmlSanitizer,
            MutationAuditService contentMutationAuditService,
            FileReferenceIndex fileReferenceIndex) {
        this.articleRepository = articleRepository;
        this.targetDepartmentRepository = targetDepartmentRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.articleHistoryRepository = articleHistoryRepository;
        this.articleReadReceiptRepository = articleReadReceiptRepository;
        this.articleViewLogRepository = articleViewLogRepository;
        this.categoryRepository = categoryRepository;
        this.userNoteRepository = userNoteRepository;
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.quizGateChecker = quizGateChecker;
        this.permissionChecker = permissionChecker;
        this.scopeResolver = scopeResolver;
        this.tagSyncService = tagSyncService;
        this.articleQueryService = articleQueryService;
        this.articleViewQueryService = articleViewQueryService;
        this.eligibleOperatorsService = eligibleOperatorsService;
        this.searchReindexService = searchReindexService;
        this.contentLifecycleService = contentLifecycleService;
        this.articleHtmlSanitizer = articleHtmlSanitizer;
        this.contentMutationAuditService = contentMutationAuditService;
        this.fileReferenceIndex = fileReferenceIndex;
    }

    @GetMapping("/api/articles")
    public ResponseEntity<?> getArticles(
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> targetDepartments = resolveTargetDepartments(id);

        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, targetDepartments, user);
        if (visibility != null) {
            return visibility;
        }

        return ResponseEntity.ok(ArticleResponse.from(article, targetDepartments));
    }

    @PostMapping("/api/articles")
    @Transactional
    public ResponseEntity<?> createArticle(
            @Valid @RequestBody ArticleRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        Article article = new Article();
        applySharedFields(article, request);
        // Never client-supplied (routers/articles.py:231): the author is
        // always the authenticated editor.
        article.setAuthorId(user.getId());
        // Unlike update, create applies last_verified_at exactly as sent
        // (routers/articles.py:227-235 never pops it before the entity is
        // built) -- ArticleRequest's own javadoc explains the asymmetry.
        article.setLastVerifiedAt(request.lastVerifiedAt());
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        if ("published".equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(TbilisiTime.now());
        }

        Article saved = articleRepository.saveAndFlush(article);
        replaceTargetDepartments(saved.getId(), request.targetDepartments());
        tagSyncService.sync("article", saved.getId(), saved.getTags());
        searchReindexService.reindexArticle(saved);

        ArticleHistory history = new ArticleHistory();
        history.setArticleId(saved.getId());
        history.setTitle(saved.getTitle());
        history.setContent(saved.getContent());
        history.setUpdatedBy(user.getId());
        history.setVersionId(1);
        history.setUpdatedAt(saved.getCreatedAt());
        articleHistoryRepository.save(history);

        List<String> savedTargets = resolveTargetDepartments(saved.getId());
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("article", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "CREATE", "article", saved.getId(), saved.getTitle(), null,
                MutationAuditService.articleSnapshot(saved, savedTargets));

        return ResponseEntity.ok(ArticleResponse.from(saved, savedTargets));
    }

    @PutMapping("/api/articles/{id}")
    @Transactional
    public ResponseEntity<?> updateArticle(
            @PathVariable Long id, @Valid @RequestBody ArticleRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> previousTargets = resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, previousTargets);

        articleHistoryRepository.archiveIfMissing(
                article.getId(), article.getTitle(), article.getContent(), user.getId(), article.getVersion(),
                article.getUpdatedAt() != null ? article.getUpdatedAt() : TbilisiTime.now());

        // author_id and last_verified_at are deliberately untouched here --
        // routers/articles.py:342-343 pops both before the update loop so
        // an edit can never silently overwrite the original author or the
        // last-verified timestamp.
        applySharedFields(article, request);
        replaceTargetDepartments(id, request.targetDepartments());

        if ("published".equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(TbilisiTime.now());
        }
        article.setVersion(article.getVersion() + 1);
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        tagSyncService.sync("article", id, saved.getTags());
        searchReindexService.reindexArticle(saved);

        ArticleHistory history = new ArticleHistory();
        history.setArticleId(id);
        history.setTitle(saved.getTitle());
        history.setContent(saved.getContent());
        history.setUpdatedBy(user.getId());
        history.setVersionId(saved.getVersion());
        history.setUpdatedAt(saved.getUpdatedAt());
        articleHistoryRepository.save(history);

        List<String> savedTargets = resolveTargetDepartments(saved.getId());
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("article", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "UPDATE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, savedTargets));

        return ResponseEntity.ok(ArticleResponse.from(saved, savedTargets));
    }

    /**
     * Partial save for work in progress. <b>Drafts only</b> -- see below.
     *
     * <h3>BL-03: why this endpoint may not touch a published article</h3>
     *
     * {@link #updateArticle} performs the full ritual: archive the current
     * state, apply, {@code version + 1}, save, reindex, write a history row.
     * Autosave applied the same fields -- title, content, status,
     * published_at, target_departments, is_draft -- and then set only
     * {@code updatedAt}. No version bump, no history row, and no guard
     * restricting it to drafts, so it rewrote published articles exactly as
     * readily as drafts.
     *
     * <p>The version number is not decoration; three things key on it, and
     * all three were silently wrong:
     * <ul>
     *   <li>{@link ge.magti.portal.quiz.QuizGateChecker} looks up a passing
     *       attempt at {@code article.getVersion()}. Rewrite the body via
     *       autosave and everyone who passed the quiz on the OLD text still
     *       satisfies the gate on the new one.
     *   <li>{@code article_read_receipts} is unique on
     *       {@code (article_id, article_version, operator_id)} (V19:17), so a
     *       receipt written against the old text still reads as "this person
     *       has read the current version".
     *   <li>The {@code article_history} snapshot for version N holds the
     *       pre-autosave text while the live article at version N holds the
     *       post-autosave text -- a diff of "current vs version N" then shows
     *       changes within one version number, which the version list has no
     *       way to express.
     * </ul>
     *
     * <p>The fix is a rule rather than a heuristic: autosave is a draft-only
     * operation. Bumping the version on every autosave was the other
     * candidate and is worse -- it would write a history row per keystroke
     * batch and re-invalidate every read receipt repeatedly while an author
     * is still typing. A draft has no readers (see
     * {@link ge.magti.portal.article.ArticleQueryService}'s visibility rule:
     * {@code isDraft = false} AND published/due-scheduled), so it has no
     * receipts and no quiz passes to invalidate, which is exactly why
     * autosave can stay cheap there.
     *
     * <p>Publishing therefore has to go through {@code PUT}, which archives
     * and bumps. Rejected <i>before</i> any field is applied, deliberately:
     * this method is {@code @Transactional} over a managed entity, so
     * mutating first and returning an error response later would still flush
     * the mutation at commit.
     *
     * <p><b>Known, accepted residue:</b> a draft that already has a history
     * row for its current version can still drift from that snapshot as it
     * is autosaved. That is the third bullet above, reduced from "published
     * content diverges from what readers acknowledged" to "a draft's own
     * snapshot lags its live text", visible only to its author. Fixing it
     * would mean either version churn or rewriting history, both worse than
     * the drift.
     */
    @PatchMapping("/api/articles/{id}/autosave")
    @Transactional
    public ResponseEntity<?> autosaveArticle(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> previousTargetDepartments = resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, previousTargetDepartments);

        if (isReaderVisible(article.getStatus(), article.isDraft(), article.getPublishedAt())) {
            return publishedArticleNotAutosavable();
        }
        if (isReaderVisible(
                body.containsKey("status") ? (String) body.get("status") : article.getStatus(),
                body.containsKey("is_draft") ? (Boolean) body.get("is_draft") : article.isDraft(),
                prospectivePublishedAt(body, article))) {
            return publishedArticleNotAutosavable();
        }

        // exclude_unset semantics (routers/articles.py:401): only fields the
        // client actually sent in this partial payload are touched -- a
        // typed record can't tell "absent" from "not included", so this one
        // endpoint reads the raw JSON object as a map instead (containsKey
        // is true even for an explicit JSON null, matching Pydantic's own
        // "set to None" still counting as sent).
        if (body.containsKey("title")) {
            article.setTitle((String) body.get("title"));
        }
        if (body.containsKey("content")) {
            article.setContent(articleHtmlSanitizer.sanitize((String) body.get("content")));
        }
        if (body.containsKey("category_id")) {
            Object value = body.get("category_id");
            article.setCategoryId(value == null ? null : ((Number) value).longValue());
        }
        if (body.containsKey("tags")) {
            article.setTags((String) body.get("tags"));
        }
        if (body.containsKey("target_departments")) {
            Object value = body.get("target_departments");
            List<String> targetDepartments = value == null ? List.of()
                    : ((List<?>) value).stream().map(String::valueOf).toList();
            // routers/articles.py:405 -- `if target_departments:` -- an
            // empty/absent-valued list leaves the existing rows untouched.
            if (!targetDepartments.isEmpty()) {
                article.setTargetDepartment(
                        targetDepartments.contains("All") ? "All" : targetDepartments.get(0));
                replaceTargetDepartments(id, targetDepartments);
            }
        }
        if (body.containsKey("status")) {
            article.setStatus((String) body.get("status"));
        }
        if (body.containsKey("published_at")) {
            Object value = body.get("published_at");
            article.setPublishedAt(value == null ? null : OffsetDateTime.parse((String) value));
        }
        if (body.containsKey("attachment_url")) {
            article.setAttachmentUrl((String) body.get("attachment_url"));
        }
        if (body.containsKey("audience_profile")) {
            article.setAudienceProfile((String) body.get("audience_profile"));
        }
        if (body.containsKey("visible_to_tech_info")) {
            article.setVisibleToTechInfo((Boolean) body.get("visible_to_tech_info"));
        }
        if (body.containsKey("visible_to_service_center")) {
            article.setVisibleToServiceCenter((Boolean) body.get("visible_to_service_center"));
        }
        if (body.containsKey("is_draft")) {
            article.setDraft((Boolean) body.get("is_draft"));
        }

        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        List<String> savedTargetDepartments = resolveTargetDepartments(id);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("article", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "AUTOSAVE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, savedTargetDepartments));
        searchReindexService.reindexArticle(saved);

        return ResponseEntity.ok(ArticleAutosaveResponse.from(saved, savedTargetDepartments));
    }

    /**
     * Whether an operator could read this article -- the same condition
     * {@link ge.magti.portal.article.ArticleQueryService}'s list query
     * applies, kept in step with it deliberately: "someone might have read
     * this" is exactly what makes a silent rewrite dangerous (BL-03).
     */
    private static boolean isReaderVisible(String status, boolean isDraft, OffsetDateTime publishedAt) {
        if (isDraft) {
            return false;
        }
        if ("published".equals(status)) {
            return true;
        }
        return "scheduled".equals(status) && publishedAt != null && !publishedAt.isAfter(TbilisiTime.now());
    }

    private static OffsetDateTime prospectivePublishedAt(Map<String, Object> body, Article article) {
        if (!body.containsKey("published_at")) {
            return article.getPublishedAt();
        }
        Object value = body.get("published_at");
        return value == null ? null : OffsetDateTime.parse((String) value);
    }

    private static ResponseEntity<Map<String, String>> publishedArticleNotAutosavable() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "detail", "გამოქვეყნებული სტატიის ავტოშენახვა შეუძლებელია — გამოიყენეთ შენახვა, "
                        + "რომ ვერსია განახლდეს და თანამშრომლებს ხელახლა წაკითხვა მოეთხოვოთ"));
    }

    @DeleteMapping("/api/articles/{id}")
    @Transactional
    public ResponseEntity<?> deleteArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        ContentLifecycleService.Status status = contentLifecycleService.moveToTrash(
                ContentLifecycleService.ItemType.ARTICLE, id, user);
        if (status == ContentLifecycleService.Status.OK) {
            searchReindexService.remove(SearchReindexService.ARTICLE, id);
            return ResponseEntity.noContent().build();
        }
        return ContentTrashController.response(status, "სტატია სანაგვეში გადავიდა");
    }

    @PostMapping("/api/articles/{id}/archive")
    @Transactional
    public ResponseEntity<?> archiveArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesArchivePermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        if ("archived".equals(article.getStatus())) {
            return ResponseEntity.ok(ArticleResponse.from(article, resolveTargetDepartments(id)));
        }

        List<String> targetDepartments = resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);
        article.setStatus("archived");
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        contentMutationAuditService.recordSuccess(
                user, "ARCHIVE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, targetDepartments));
        return ResponseEntity.ok(ArticleResponse.from(saved, targetDepartments));
    }

    @PostMapping("/api/articles/{id}/unarchive")
    @Transactional
    public ResponseEntity<?> unarchiveArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesArchivePermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        if (!"archived".equals(article.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "სტატია არ არის არქივში"));
        }

        List<String> targetDepartments = resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);
        article.setStatus("published");
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        contentMutationAuditService.recordSuccess(
                user, "UNARCHIVE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, targetDepartments));
        return ResponseEntity.ok(ArticleResponse.from(saved, targetDepartments));
    }

    @PostMapping("/api/articles/bulk-archive")
    @Transactional
    public ResponseEntity<?> bulkArchiveArticles(
            @Valid @RequestBody ArticleBulkArchiveRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireArticlesArchivePermission(user);
        if (denial != null) {
            return denial;
        }

        String target = request.archive() ? "archived" : "published";
        List<Article> rows = articleRepository.findAllById(request.ids());
        Set<Long> found = rows.stream().map(Article::getId).collect(Collectors.toSet());
        List<Long> skipped = new ArrayList<>();
        for (Long requestedId : request.ids()) {
            if (!found.contains(requestedId)) {
                skipped.add(requestedId);
            }
        }

        int updated = 0;
        for (Article article : rows) {
            if (target.equals(article.getStatus())) {
                skipped.add(article.getId());
                continue;
            }
            List<String> targetDepartments = resolveTargetDepartments(article.getId());
            Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);
            article.setStatus(target);
            article.setUpdatedAt(TbilisiTime.now());
            Article saved = articleRepository.saveAndFlush(article);
            contentMutationAuditService.recordSuccess(
                    user, request.archive() ? "ARCHIVE" : "UNARCHIVE", "article",
                    saved.getId(), saved.getTitle(), before,
                    MutationAuditService.articleSnapshot(saved, targetDepartments));
            updated++;
        }

        return ResponseEntity.ok(new ArticleBulkArchiveResponse(updated, target, skipped));
    }

    @GetMapping("/api/articles/{id}/note")
    public ResponseEntity<?> getUserNote(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        ResponseEntity<Map<String, String>> lookup = requireVisibleArticle(id, user);
        if (lookup != null) {
            return lookup;
        }

        Optional<UserNote> note = userNoteRepository.findByUserIdAndArticleId(user.getId(), id);
        if (note.isEmpty()) {
            // response_model=Optional[UserNoteResponse] -- FastAPI serializes
            // a None return as the literal JSON `null`, not an empty body;
            // ResponseEntity.ok(null) would otherwise make Spring write zero
            // bytes, which a client's response.json() would fail to parse.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
        }
        return ResponseEntity.ok(UserNoteResponse.from(note.get()));
    }

    @PutMapping("/api/articles/{id}/note")
    @Transactional
    public ResponseEntity<?> putUserNote(
            @PathVariable Long id, @Valid @RequestBody UserNoteRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        ResponseEntity<Map<String, String>> lookup = requireVisibleArticle(id, user);
        if (lookup != null) {
            return lookup;
        }

        UserNote note = userNoteRepository.findByUserIdAndArticleId(user.getId(), id).orElseGet(UserNote::new);
        boolean isNew = note.getId() == null;
        note.setContent(request.content());
        if (isNew) {
            note.setUserId(user.getId());
            note.setArticleId(id);
            note.setCreatedAt(TbilisiTime.now());
        }
        note.setUpdatedAt(TbilisiTime.now());
        UserNote saved = userNoteRepository.save(note);
        return ResponseEntity.ok(UserNoteResponse.from(saved));
    }

    @PostMapping("/api/articles/{id}/verify")
    @Transactional
    public ResponseEntity<?> verifyArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> targetDepartments = resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);
        article.setLastVerifiedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        contentMutationAuditService.recordSuccess(
                user, "VERIFY", "article", id, saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, targetDepartments));
        return ResponseEntity.ok(ArticleResponse.from(saved, targetDepartments));
    }

    @GetMapping("/api/admin/articles/stale")
    public ResponseEntity<?> getStaleArticles(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        OffsetDateTime cutoff = TbilisiTime.now().minusDays(180);
        List<StaleArticleResponse> stale = CompleteResultGuard.enforce(articleRepository
                .findStaleReferences("published", cutoff, CompleteResultGuard.sentinelPage())).stream()
                .map(a -> new StaleArticleResponse(
                        a.id(), a.title(), resolveTargetDepartments(a.id()), a.lastVerifiedAt(),
                        Duration.between(a.lastVerifiedAt(), TbilisiTime.now()).toDays()))
                .toList();
        return ResponseEntity.ok(stale);
    }

    @GetMapping("/api/articles/{id}/related")
    public ResponseEntity<?> getRelatedArticles(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        // routers/articles.py:1582-1584 -- a plain lookup, not get_or_404: a
        // missing source article returns an empty list, not a 404.
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        Article source = found.get();
        ResponseEntity<Map<String, String>> visibility =
                assertArticleVisible(source, resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        List<ArticleReferenceItem> published = CompleteResultGuard.enforce(
                        articleRepository.findReferencesByStatus(
                                "published", CompleteResultGuard.sentinelPage())).stream()
                .filter(a -> !a.id().equals(id))
                .toList();
        Set<Long> candidateIds = published.stream().map(ArticleReferenceItem::id).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle =
                articleTargetQueryService.targetDepartmentsByArticleWithinLimit(candidateIds);
        boolean isAdmin = user.getRole().isContentAdmin();
        // Deliberately exact-match + "All" only, NOT DepartmentMatcher's
        // prefix-aware rule -- routers/articles.py:1596-1601 narrows this
        // one candidate filter differently than get_articles' own list
        // query does, and this port carries that difference forward
        // unchanged rather than unifying it.
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
                    .sorted(Comparator.comparing(ArticleReferenceItem::createdAt).reversed())
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

    @GetMapping("/api/articles/{id}/history")
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> getArticleHistory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        // No get_or_404 here, matching routers/articles.py:539-565 exactly:
        // a missing article_id isn't checked separately, it just yields zero
        // matching history rows -- an empty list, not a 404.
        HistoryPayloadGuard.enforceFullResponseCharacters(
                articleHistoryRepository.totalContentCharactersByArticleId(id));
        List<ArticleHistory> history = ArticleEvidenceCardinalityGuard.enforceWithinLimit(
                articleHistoryRepository.findByArticleIdOrderByUpdatedAtDesc(
                        id, PageRequest.of(0, ArticleEvidenceCardinalityGuard.MAX_ROWS + 1)));
        Set<Long> authorIds = history.stream().map(ArticleHistory::getUpdatedBy).collect(Collectors.toSet());
        Map<Long, String> namesByUserId = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        // INNER JOIN semantics (routers/articles.py:548-549's .join(User, ...)):
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
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
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
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, resolveTargetDepartments(id), user);
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
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        List<String> targetDepartments = resolveTargetDepartments(id);
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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        // Self-healing (routers/articles.py:943-948): legacy articles
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

        // OUTER JOIN semantics (routers/articles.py:952's .outerjoin), unlike
        // get_article_history's INNER JOIN above: a row survives even when
        // updated_by has no matching user, with author_name = null.
        List<ArticleVersionItemResponse> versions = history.stream()
                .map(h -> new ArticleVersionItemResponse(
                        h.versionId() != null ? h.versionId() : 0, h.title(), h.updatedAt(),
                        namesByUserId.get(h.updatedBy()), h.id()))
                .sorted(Comparator.comparingInt(ArticleVersionItemResponse::version).reversed())
                .toList();
        return ResponseEntity.ok(versions);
    }

    /** Port of _diff_ordered_by_version (routers/articles.py:568-583). */
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

    @GetMapping("/api/articles/{id}/read-receipts")
    public ResponseEntity<?> getArticleReadReceipts(
            @PathVariable Long id, @RequestParam(required = false) Integer version, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireReadEvidenceAccess(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();

        OffsetDateTime dueDate = requiredReadingRepository.findFirstByItemTypeAndItemId("article", id)
                .map(RequiredReading::getDueDate).orElse(null);
        int targetVersion = version != null ? version : article.getVersion();
        List<User> eligibleUsers = eligibleOperatorsService.forArticle(article, resolveTargetDepartments(id));
        Scope namedScope = scopeResolver.resolveGroupLeadership(user);
        boolean hasOrganisationAggregate = permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE);
        List<User> aggregateUsers = hasOrganisationAggregate || namedScope.unscoped()
                ? eligibleUsers
                : eligibleUsers.stream().filter(candidate -> namedScope.includesTeam(candidate.getTeamId())).toList();
        List<User> namedUsers = namedScope.unscoped()
                ? eligibleUsers
                : eligibleUsers.stream().filter(candidate -> namedScope.includesTeam(candidate.getTeamId())).toList();

        List<ArticleReadReceipt> receipts = ArticleEvidenceCardinalityGuard.enforceWithinLimit(
                articleReadReceiptRepository.findByArticleIdSnapshotAndArticleVersion(
                        id, targetVersion,
                        PageRequest.of(0, ArticleEvidenceCardinalityGuard.MAX_ROWS + 1)));
        Map<Long, ArticleReadReceipt> receiptByOperator = receipts.stream()
                .filter(r -> r.getOperatorId() != null)
                .collect(Collectors.toMap(ArticleReadReceipt::getOperatorId, r -> r, (a, b) -> a));

        Set<Long> aggregateUserIds = aggregateUsers.stream().map(User::getId).collect(Collectors.toSet());
        int eligibleCount = aggregateUsers.size();
        int readCount = (int) aggregateUserIds.stream().filter(receiptByOperator::containsKey).count();
        int lateReadCount = dueDate == null ? 0 : (int) aggregateUserIds.stream()
                .map(receiptByOperator::get)
                .filter(Objects::nonNull)
                .filter(receipt -> receipt.getReadAt() != null && receipt.getReadAt().isAfter(dueDate))
                .count();

        Set<Long> processedOperatorIds = new HashSet<>();
        List<ArticleReadReceiptRowResponse> rows = new ArrayList<>();
        for (User u : namedUsers) {
            processedOperatorIds.add(u.getId());
            ArticleReadReceipt receipt = receiptByOperator.get(u.getId());
            if (receipt != null) {
                rows.add(buildReceiptRow(receipt, dueDate));
            } else {
                rows.add(new ArticleReadReceiptRowResponse(u.getId(), u.getName(), u.getDepartment(),
                        null, null, false, false, TbilisiTime.format(dueDate), "unread"));
            }
        }
        Set<Long> visibleDetachedOperatorIds = visibleDetachedOperatorIds(
                receipts, processedOperatorIds, namedScope);
        // Detached/orphaned snapshot rows: a receipt whose operator is no
        // longer eligible (left the department, deactivated, ...) still
        // shows, from its own frozen snapshot -- routers/articles.py:1100-1122.
        for (ArticleReadReceipt receipt : receipts) {
            if (!processedOperatorIds.contains(receipt.getOperatorId())
                    && (namedScope.unscoped() || visibleDetachedOperatorIds.contains(receipt.getOperatorId()))) {
                rows.add(buildReceiptRow(receipt, dueDate));
            }
        }
        ArticleEvidenceCardinalityGuard.enforceResponseRowLimit(rows.size());

        return ResponseEntity.ok(new ArticleReadReceiptResponse(
                id, article.getTitle(), targetVersion, eligibleCount, readCount,
                eligibleCount - readCount, lateReadCount, rows));
    }

    @PostMapping("/api/articles/{id}/read-receipt")
    @Transactional
    public ResponseEntity<?> createArticleReadReceipt(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }
        ResponseEntity<Map<String, String>> quizGate = requireQuizPassed(article, user);
        if (quizGate != null) {
            return quizGate;
        }

        OffsetDateTime readAt = TbilisiTime.now();
        articleReadReceiptRepository.upsert(id, article.getTitle(), article.getVersion(), user.getId(),
                user.getName(), user.getEmail(), user.getDepartment(), readAt);
        ArticleReadReceipt receipt = articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(id, article.getVersion(), user.getId())
                .orElseThrow();

        // Compliance bridge (routers/articles.py:1207-1233): prefix-aware,
        // unlike EligibleOperatorsService's exact-match rule -- this one
        // reuses the same [dept, deptPrefix, "All"] pattern get_articles'
        // own list query uses. Only fills gaps: an already-"read"
        // ReadStatus keeps its original read_at.
        String deptPrefix = DepartmentMatcher.splitGroup(user.getDepartment()).prefix();
        List<RequiredReading> covering = CompleteResultGuard.enforce(
                requiredReadingRepository.findByItemTypeAndItemIdAndTargetDepartmentIn(
                        "article", id, List.of(user.getDepartment(), deptPrefix, "All"),
                        CompleteResultGuard.sentinelPage()));
        for (RequiredReading rr : covering) {
            ReadStatus stat = readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), rr.getId())
                    .orElseGet(() -> {
                        ReadStatus fresh = new ReadStatus();
                        fresh.setUserId(user.getId());
                        fresh.setRequiredReadingId(rr.getId());
                        return fresh;
                    });
            if ("read".equals(stat.getStatus())) {
                continue;
            }
            stat.setStatus("read");
            stat.setReadAt(TbilisiTime.now());
            stat.setOperatorDepartmentSnapshot(user.getDepartment());
            readStatusRepository.save(stat);
        }

        return ResponseEntity.ok(new CreateReadReceiptResponse("success", receipt.getReadAt(), receipt.getArticleVersion()));
    }

    @GetMapping("/api/articles/{id}/read-receipt/me")
    public ResponseEntity<?> getMyArticleReadReceiptStatus(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        Optional<ArticleReadReceipt> receipt = articleReadReceiptRepository
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(id, article.getVersion(), user.getId());
        if (receipt.isPresent()) {
            return ResponseEntity.ok(new MyReadReceiptStatusResponse(
                    true, receipt.get().getReadAt(), receipt.get().getArticleVersion(), article.getVersion()));
        }
        return ResponseEntity.ok(new MyReadReceiptStatusResponse(false, null, null, article.getVersion()));
    }

    @PostMapping("/api/articles/{id}/view")
    public ResponseEntity<?> trackArticleView(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, resolveTargetDepartments(id), user);
        if (visibility != null) {
            return visibility;
        }

        ArticleViewLog log = new ArticleViewLog();
        log.setArticleId(article.getId());
        // BL-12: same value, but this one has no FK and therefore survives
        // the article's deletion, which is what the read paths filter on.
        log.setArticleIdSnapshot(article.getId());
        log.setArticleTitleSnapshot(article.getTitle());
        log.setArticleVersion(article.getVersion());
        log.setOperatorId(user.getId());
        log.setOperatorNameSnapshot(user.getName());
        log.setOperatorEmailSnapshot(user.getEmail());
        log.setOperatorDepartmentSnapshot(user.getDepartment());
        log.setViewedAt(TbilisiTime.now());
        articleViewLogRepository.save(log);

        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @GetMapping("/api/articles/{id}/views")
    public ResponseEntity<?> getArticleViews(
            @PathVariable Long id, @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();

        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        ArticleViewQueryService.ViewPage page = articleViewQueryService.query(
                id, version, safeOffset, safeLimit);
        List<ArticleViewRowResponse> rows = page.rows().stream()
                .map(v -> new ArticleViewRowResponse(v.getOperatorId(), v.getOperatorNameSnapshot(),
                        v.getOperatorEmailSnapshot(), v.getOperatorDepartmentSnapshot(), v.getArticleVersion(),
                        TbilisiTime.format(v.getViewedAt())))
                .toList();

        return ResponseEntity.ok(new ArticleViewsResponse(
                id, article.getVersion(), version, page.totalViews(), page.uniqueViewers(), rows));
    }

    @GetMapping("/api/me/recently-viewed")
    public ResponseEntity<?> getMyRecentlyViewed(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<ArticleViewLog> rows = articleViewLogRepository.findTop30ByOperatorIdOrderByViewedAtDesc(user.getId());
        Set<Long> articleIds = rows.stream().map(ArticleViewLog::getArticleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> titlesByArticleId = articleRepository.findAllById(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));

        Set<Long> seenIds = new LinkedHashSet<>();
        List<RecentlyViewedItemResponse> items = new ArrayList<>();
        for (ArticleViewLog row : rows) {
            Long articleId = row.getArticleId();
            // INNER JOIN semantics (routers/articles.py:1376): a view of a
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

    private ArticleReadReceiptRowResponse buildReceiptRow(ArticleReadReceipt receipt, OffsetDateTime dueDate) {
        boolean isLate = false;
        String status = "read";
        if (dueDate != null && receipt.getReadAt() != null && receipt.getReadAt().isAfter(dueDate)) {
            isLate = true;
            status = "late_read";
        }
        return new ArticleReadReceiptRowResponse(
                receipt.getOperatorId(), receipt.getOperatorNameSnapshot(), receipt.getOperatorDepartmentSnapshot(),
                TbilisiTime.format(receipt.getReadAt()), receipt.getArticleVersion(),
                true, isLate, TbilisiTime.format(dueDate), status);
    }

    private Set<Long> visibleDetachedOperatorIds(
            List<ArticleReadReceipt> receipts, Set<Long> processedOperatorIds, Scope namedScope) {
        if (namedScope.unscoped() || namedScope.readsNobody()) {
            return Set.of();
        }
        List<Long> detachedIds = receipts.stream()
                .map(ArticleReadReceipt::getOperatorId)
                .filter(Objects::nonNull)
                .filter(operatorId -> !processedOperatorIds.contains(operatorId))
                .distinct()
                .toList();
        return userRepository.findAllById(detachedIds).stream()
                .filter(operator -> namedScope.includesTeam(operator.getTeamId()))
                .map(User::getId)
                .collect(Collectors.toSet());
    }

    /** Port of _check_quiz_gate -- now delegated to the shared {@link QuizGateChecker}, which ComplianceController's mark-read reuses too. */
    private ResponseEntity<Map<String, String>> requireQuizPassed(Article article, User user) {
        return quizGateChecker.denialFor(article, user);
    }

    private void applySharedFields(Article article, ArticleRequest request) {
        article.setTitle(request.title());
        article.setContent(articleHtmlSanitizer.sanitize(request.content()));
        article.setCategoryId(request.categoryId());
        article.setTags(request.tags());
        article.setTargetDepartment(request.legacyTargetDepartment());
        article.setStatus(request.statusOrDefault());
        article.setYoutubeId(request.youtubeId());
        article.setPublishedAt(request.publishedAt());
        article.setAttachmentUrl(request.attachmentUrl());
        article.setAudienceProfile(request.audienceProfileOrDefault());
        article.setVisibleToTechInfo(request.visibleToTechInfoOrDefault());
        article.setVisibleToServiceCenter(request.visibleToServiceCenterOrDefault());
        article.setDraft(request.isDraftOrDefault());
        article.setQuizEnabled(request.quizEnabledOrDefault());
    }

    private List<String> resolveTargetDepartments(Long articleId) {
        return articleTargetQueryService.targetDepartmentsForArticleWithinLimit(articleId);
    }

    private void replaceTargetDepartments(Long articleId, List<String> departments) {
        targetDepartmentRepository.deleteByArticleId(articleId);
        for (String department : departments) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(articleId);
            row.setDepartment(department);
            targetDepartmentRepository.save(row);
        }
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    /** Port of _assert_article_visible (routers/articles.py:61-94). */
    /**
     * The predicate itself now lives in {@link ArticleVisibility}, because
     * {@code /uploads/{filename}} needs the same answer before serving a file
     * that an article carries (DEC-P01). This wrapper keeps the 404-shaped
     * response every caller in here already expects.
     */
    private static ResponseEntity<Map<String, String>> assertArticleVisible(
            Article article, List<String> targetDepartments, User user) {
        return ArticleVisibility.isVisible(article, targetDepartments, user) ? null : notFoundMap();
    }

    /** Combines the get_or_404 + _assert_article_visible pair every note/quiz-style child route repeats. */
    private ResponseEntity<Map<String, String>> requireVisibleArticle(Long articleId, User user) {
        Optional<Article> found = articleRepository.findById(articleId);
        if (found.isEmpty()) {
            return notFoundMap();
        }
        return assertArticleVisible(found.get(), resolveTargetDepartments(articleId), user);
    }

    /**
     * Port of get_related_articles' narrower department filter
     * (routers/articles.py:1596-1601) -- exact match or "All" only, no
     * {@link DepartmentMatcher} prefix support. Deliberately not reusing
     * {@code assertArticleVisible}'s dept check: that one calls
     * DepartmentMatcher.matches, this endpoint's own Python source doesn't.
     */
    private static boolean relatedArticleDeptMatches(String userDepartment, List<String> targets) {
        for (String target : targets) {
            if ("All".equals(target) || Objects.equals(target, userDepartment)) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Map<String, String>> notFoundMap() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireContentAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!user.getRole().isContentAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireReadEvidenceAccess(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)
                || !scopeResolver.resolveGroupLeadership(user).readsNobody()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", "წვდომა უარყოფილია: ოფიციალური წაკითხვის მონაცემები თქვენს ჯგუფს არ ეკუთვნის"));
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireContentManage(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireArticlesArchivePermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.ARTICLES_ARCHIVE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    /**
     * Bug #314 fix, user-confirmed 2026-08-13: {@code articles.edit} was
     * defined, defaulted onto content_admin, and settable per-user via
     * {@code PUT /api/users/{id}/permissions} --
     * but no endpoint ever consulted them (confirmed present in Python too,
     * routers/articles.py's CRUD depends only on {@code get_current_admin_user},
     * never {@code require_permission}). Revoking a content_admin's
     * articles.edit did nothing; the permission editor was lying. Now
     * actually enforced on the 4 mutating endpoints, same {@link
     * PermissionChecker} pattern as {@link #requireArticlesArchivePermission}.
     */
    private ResponseEntity<Map<String, String>> requireArticlesEditPermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.ARTICLES_EDIT)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

}
