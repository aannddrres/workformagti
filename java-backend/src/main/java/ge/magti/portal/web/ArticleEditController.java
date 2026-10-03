package ge.magti.portal.web;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ArticleHtmlSanitizer;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.TagSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;

/**
 * Writing an article's content: create, update and the draft-only autosave.
 * Part of the article API split described on {@link ArticleController}.
 */
@RestController
public class ArticleEditController {

    static final String STALE_ARTICLE_EDIT_DETAIL =
            "ეს სტატია თქვენ მიერ გახსნის შემდეგ სხვამ შეცვალა. დახურეთ ფორმა, გახსენით თავიდან "
                    + "და შეიტანეთ თქვენი ცვლილება ახალ ვერსიაში.";

    private final ArticleRepository articleRepository;
    private final ArticleHistoryRepository articleHistoryRepository;
    private final TagSyncService tagSyncService;
    private final SearchReindexService searchReindexService;
    private final ArticleHtmlSanitizer articleHtmlSanitizer;
    private final MutationAuditService contentMutationAuditService;
    private final FileReferenceIndex fileReferenceIndex;
    private final ArticleEndpointSupport articleSupport;

    public ArticleEditController(
            ArticleRepository articleRepository,
            ArticleHistoryRepository articleHistoryRepository,
            TagSyncService tagSyncService,
            SearchReindexService searchReindexService,
            ArticleHtmlSanitizer articleHtmlSanitizer,
            MutationAuditService contentMutationAuditService,
            FileReferenceIndex fileReferenceIndex,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.articleHistoryRepository = articleHistoryRepository;
        this.tagSyncService = tagSyncService;
        this.searchReindexService = searchReindexService;
        this.articleHtmlSanitizer = articleHtmlSanitizer;
        this.contentMutationAuditService = contentMutationAuditService;
        this.fileReferenceIndex = fileReferenceIndex;
        this.articleSupport = articleSupport;
    }

    @PostMapping("/api/articles")
    @Transactional
    public ResponseEntity<?> createArticle(
            @Valid @RequestBody ArticleRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        ResponseEntity<Map<String, String>> unknownTargets = articleSupport.unknownDepartments(request.targetDepartments());
        if (unknownTargets != null) {
            return unknownTargets;
        }
        Article article = new Article();
        applySharedFields(article, request);
        // Never client-supplied: the author is
        // always the authenticated editor.
        article.setAuthorId(user.getId());
        // Unlike update, create applies last_verified_at exactly as sent
        // -- ArticleRequest's own javadoc explains the asymmetry.
        article.setLastVerifiedAt(request.lastVerifiedAt());
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article.setVersion(1);
        if ("published".equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(TbilisiTime.now());
        }

        Article saved = articleRepository.saveAndFlush(article);
        articleSupport.replaceTargetDepartments(saved.getId(), request.targetDepartments());
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

        List<String> savedTargets = articleSupport.resolveTargetDepartments(saved.getId());
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
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        if (ArticleVisibility.isPrivateDraftOfAnother(article, user)) {
            return notFound();
        }
        ResponseEntity<Map<String, String>> unknownTargets = articleSupport.unknownDepartments(request.targetDepartments());
        if (unknownTargets != null) {
            return unknownTargets;
        }
        if (request.lockVersion() != null && request.lockVersion() != article.getLockVersion()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", STALE_ARTICLE_EDIT_DETAIL));
        }
        List<String> previousTargets = articleSupport.resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, previousTargets);

        articleHistoryRepository.archiveIfMissing(
                article.getId(), article.getTitle(), article.getContent(), user.getId(), article.getVersion(),
                article.getUpdatedAt() != null ? article.getUpdatedAt() : TbilisiTime.now());

        // author_id and last_verified_at are deliberately untouched here, so
        // an edit can never silently overwrite the original author or the
        // last-verified timestamp.
        OffsetDateTime previousPublishedAt = article.getPublishedAt();
        applySharedFields(article, request);
        articleSupport.replaceTargetDepartments(id, request.targetDepartments());

        if ("published".equals(article.getStatus()) && article.getPublishedAt() == null) {
            // The editor sends no date unless it schedules one, so every save of
            // a published article used to re-stamp it "published now" -- a typo
            // fix moved a year-old article to the top as new (simulation,
            // 2026-10-01). A date already reached stays; one still ahead was a
            // schedule, and publishing now is now.
            OffsetDateTime now = TbilisiTime.now();
            article.setPublishedAt(previousPublishedAt != null && !previousPublishedAt.isAfter(now)
                    ? previousPublishedAt : now);
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

        List<String> savedTargets = articleSupport.resolveTargetDepartments(saved.getId());
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
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        if (ArticleVisibility.isPrivateDraftOfAnother(article, user)) {
            return notFound();
        }
        if (body.containsKey("status") && !(body.get("status") == null
                || body.get("status") instanceof String status && status.matches(ArticleRequest.STATUS_PATTERN))) {
            // PUT has refused an unknown status since the "pubished" typo; this
            // path read the raw map and stored anything -- "pubished" hid the
            // article from every reader, "trashed" put it in no list and no
            // trash either (audit 2026-10-01).
            return ResponseEntity.badRequest().body(Map.of("detail", ArticleRequest.STATUS_MESSAGE));
        }
        if (body.get("content") instanceof String content && content.length() > ArticleRequest.MAX_CONTENT_CHARS) {
            // The cap ArticleRequest puts on create and update; this map path had none.
            return ResponseEntity.badRequest().body(Map.of("detail", ArticleRequest.CONTENT_TOO_LONG));
        }
        List<String> previousTargetDepartments = articleSupport.resolveTargetDepartments(id);
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

        // exclude_unset semantics: only fields the
        // client actually sent in this partial payload are touched -- a
        // typed record can't tell "absent" from "not included", so this one
        // endpoint reads the raw JSON object as a map instead (containsKey
        // is true even for an explicit JSON null, which still counts as
        // sent).
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
            // An empty/absent-valued list leaves the existing rows untouched.
            if (!targetDepartments.isEmpty()) {
                article.setTargetDepartment(
                        targetDepartments.contains("All") ? "All" : targetDepartments.get(0));
                articleSupport.replaceTargetDepartments(id, targetDepartments);
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
        List<String> savedTargetDepartments = articleSupport.resolveTargetDepartments(id);
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
    /**
     * The status-and-date clause now comes from {@link ArticleVisibility}
     * rather than being spelled out a second time here; only the
     * {@code is_draft} short-circuit stays, because this helper answers the
     * reader-surface question where nobody is the author.
     */
    private static boolean isReaderVisible(String status, boolean isDraft, OffsetDateTime publishedAt) {
        return !isDraft && ArticleVisibility.isPublishedByLifecycle(status, publishedAt);
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
}
