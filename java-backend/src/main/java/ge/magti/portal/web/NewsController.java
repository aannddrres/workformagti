package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.content.ArticleHtmlSanitizer;
import ge.magti.portal.content.PrivateDraftAccess;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.NewsHistory;
import ge.magti.portal.domain.User;
import ge.magti.portal.history.HistoryPayloadGuard;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.news.NewsHistorySummary;
import ge.magti.portal.news.NewsQueryService;
import ge.magti.portal.repository.NewsHistoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.news.NewsVisibility;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/news.py -- all 8 endpoints (get item, list, create,
 * update, delete, autosave, history, restore). News is structurally
 * simpler than Articles (single department column, no diff endpoint), so
 * this domain fits in one slice unlike Articles' 5.
 *
 * <p>Same two-gate shape as {@link ArticleController}/{@link
 * VideoController}: no/invalid token (401, English), wrong role for
 * create/update/delete/autosave/history/restore (403, English -- {@code
 * get_current_admin_user} == {@link User.Role#isContentAdmin()}).
 *
 * <p><b>Two confirmed live bugs fixed here, not faithfully reproduced --
 * see {@link NewsRequest}'s javadoc for the full writeup, presented to and
 * decided by the user 2026-08-04:</b> {@code author_id} and (on update
 * only) {@code isDraft}/{@code expiresAt} are preserved from the existing
 * row rather than reset by a full-replace, and {@code isDraft} defaults to
 * {@code false} (published) rather than {@code true} on create.
 *
 * <p>Create/update/archive/unarchive/version-restore write reconstructable
 * audit evidence in the same transaction as the payload, history and search
 * index. Delete delegates the same fail-closed rule to
 * {@link ContentLifecycleService}. There is no
 * search_cache clearing (no such cache exists yet in the Java port) and no SSE
 * broadcast (_notify -- Messaging domain isn't built yet).
 */
@RestController
public class NewsController {

    private static final String NOT_FOUND_DETAIL = "სიახლე ვერ მოიძებნა";

    private final NewsRepository newsRepository;
    private final NewsHistoryRepository newsHistoryRepository;
    private final UserRepository userRepository;
    private final NewsQueryService newsQueryService;
    private final SearchReindexService searchReindexService;
    private final ContentLifecycleService contentLifecycleService;
    private final PermissionChecker permissionChecker;
    private final ArticleHtmlSanitizer articleHtmlSanitizer;
    private final MutationAuditService contentMutationAuditService;
    private final FileReferenceIndex fileReferenceIndex;

    public NewsController(
            NewsRepository newsRepository,
            NewsHistoryRepository newsHistoryRepository,
            UserRepository userRepository,
            NewsQueryService newsQueryService,
            SearchReindexService searchReindexService,
            ContentLifecycleService contentLifecycleService,
            PermissionChecker permissionChecker,
            ArticleHtmlSanitizer articleHtmlSanitizer,
            MutationAuditService contentMutationAuditService,
            FileReferenceIndex fileReferenceIndex) {
        this.newsRepository = newsRepository;
        this.newsHistoryRepository = newsHistoryRepository;
        this.userRepository = userRepository;
        this.newsQueryService = newsQueryService;
        this.searchReindexService = searchReindexService;
        this.contentLifecycleService = contentLifecycleService;
        this.permissionChecker = permissionChecker;
        this.articleHtmlSanitizer = articleHtmlSanitizer;
        this.contentMutationAuditService = contentMutationAuditService;
        this.fileReferenceIndex = fileReferenceIndex;
    }

    /** Port of get_news_item (routers/news.py:23-44). */
    @GetMapping("/api/news/{id}")
    public ResponseEntity<?> getNewsItem(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }

        if (!NewsVisibility.isVisible(news, user)) {
            return notFound();
        }
        return ResponseEntity.ok(NewsResponse.from(news));
    }

    /** Port of get_news (routers/news.py:47-102). */
    @GetMapping("/api/news")
    public ResponseEntity<?> getNews(
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (ListQueryBounds.isInvalid(skip, limit)) {
            return ResponseEntity.badRequest().body(Map.of("detail", ListQueryBounds.INVALID_DETAIL));
        }
        List<NewsSummaryResponse> items = newsQueryService.listVisible(user, skip, limit).stream()
                .map(NewsSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(items);
    }

    /** Port of create_news (routers/news.py:105-138). */
    @PostMapping("/api/news")
    @Transactional
    public ResponseEntity<?> createNews(@Valid @RequestBody NewsRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        News news = new News();
        applySharedFields(news, request);
        news.setExpiresAt(request.expiresAt());
        news.setDraft(request.isDraftOrDefaultForCreate());
        news.setAuthorId(user.getId());
        news.setCreatedAt(TbilisiTime.now());
        News saved = newsRepository.saveAndFlush(news);
        searchReindexService.reindexNews(saved);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("news", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "CREATE", "news", saved.getId(), saved.getTitle(), null,
                MutationAuditService.newsSnapshot(saved));
        return ResponseEntity.status(HttpStatus.OK).body(NewsResponse.from(saved));
    }

    /** Port of update_news (routers/news.py:141-183). */
    @PutMapping("/api/news/{id}")
    @Transactional
    public ResponseEntity<?> updateNews(
            @PathVariable Long id, @Valid @RequestBody NewsRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }
        Map<String, Object> before = MutationAuditService.newsSnapshot(news);

        archiveCurrentState(news, user.getId());

        applySharedFields(news, request);
        // author_id/isDraft/expiresAt deliberately NOT touched here -- see
        // NewsRequest's javadoc: the edit form doesn't manage any of the
        // three, so a full-replace (Python's literal behaviour) would
        // silently null the author and un-publish/clear-expiry on every
        // unrelated edit. Preserved from the existing row instead.
        news.setVersion(news.getVersion() + 1);

        News saved = newsRepository.saveAndFlush(news);
        searchReindexService.reindexNews(saved);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("news", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "UPDATE", "news", saved.getId(), saved.getTitle(), before,
                MutationAuditService.newsSnapshot(saved));
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    /** Port of delete_news (routers/news.py:186-213). */
    @DeleteMapping("/api/news/{id}")
    @Transactional
    public ResponseEntity<?> deleteNews(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        if (!PrivateDraftAccess.canAccess(found.get().isDraft(), found.get().getAuthorId(), user)) {
            return notFound();
        }
        ContentLifecycleService.Status status = contentLifecycleService.moveToTrash(
                ContentLifecycleService.ItemType.NEWS, id, user);
        if (status == ContentLifecycleService.Status.OK) {
            searchReindexService.remove(SearchReindexService.NEWS, id);
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ContentTrashController.response(status, "სიახლე სანაგვეში გადავიდა");
    }

    @PostMapping("/api/news/{id}/archive")
    @Transactional
    public ResponseEntity<?> archiveNews(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }
        if (!news.isArchived()) {
            Map<String, Object> before = MutationAuditService.newsSnapshot(news);
            news.setExpiresAt(TbilisiTime.now());
            News saved = newsRepository.saveAndFlush(news);
            contentMutationAuditService.recordSuccess(
                    user, "ARCHIVE", "news", saved.getId(), saved.getTitle(), before,
                    MutationAuditService.newsSnapshot(saved));
        }
        searchReindexService.remove(SearchReindexService.NEWS, id);
        return ResponseEntity.ok(NewsResponse.from(news));
    }

    @PostMapping("/api/news/{id}/unarchive")
    @Transactional
    public ResponseEntity<?> unarchiveNews(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }
        if (!news.isArchived()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "სიახლე არ არის არქივში"));
        }
        Map<String, Object> before = MutationAuditService.newsSnapshot(news);
        news.setExpiresAt(null);
        News saved = newsRepository.saveAndFlush(news);
        contentMutationAuditService.recordSuccess(
                user, "UNARCHIVE", "news", saved.getId(), saved.getTitle(), before,
                MutationAuditService.newsSnapshot(saved));
        searchReindexService.reindexNews(saved);
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    /** Port of autosave_news (routers/news.py:216-238). */
    @PatchMapping("/api/news/{id}/autosave")
    @Transactional
    public ResponseEntity<?> autosaveNews(
            @PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }
        Map<String, Object> before = MutationAuditService.newsSnapshot(news);

        // routers/news.py:229-230 -- self-heals a null author_id (e.g. a
        // row affected by the now-fixed update_news bug, or any other path)
        // by attributing it to whoever is autosaving now.
        if (news.getAuthorId() == null) {
            news.setAuthorId(user.getId());
        }

        // exclude_unset semantics, same reasoning as ArticleController's
        // autosaveArticle: only fields actually present in this partial
        // payload are touched.
        if (body.containsKey("title")) {
            news.setTitle((String) body.get("title"));
        }
        if (body.containsKey("content")) {
            news.setContent(articleHtmlSanitizer.sanitize((String) body.get("content")));
        }
        if (body.containsKey("target_department")) {
            news.setTargetDepartment((String) body.get("target_department"));
        }
        if (body.containsKey("attachment_url")) {
            news.setAttachmentUrl((String) body.get("attachment_url"));
        }
        if (body.containsKey("visible_to_tech_info")) {
            news.setVisibleToTechInfo((Boolean) body.get("visible_to_tech_info"));
        }
        if (body.containsKey("visible_to_service_center")) {
            news.setVisibleToServiceCenter((Boolean) body.get("visible_to_service_center"));
        }
        if (body.containsKey("expires_at")) {
            Object value = body.get("expires_at");
            news.setExpiresAt(value == null ? null : OffsetDateTime.parse((String) value));
        }
        if (body.containsKey("is_draft")) {
            news.setDraft((Boolean) body.get("is_draft"));
        }

        News saved = newsRepository.saveAndFlush(news);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("news", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "AUTOSAVE", "news", saved.getId(), saved.getTitle(), before,
                MutationAuditService.newsSnapshot(saved));
        searchReindexService.reindexNews(saved);
        return ResponseEntity.ok(NewsAutosaveResponse.from(saved));
    }

    /** Port of get_news_history (routers/news.py:241-265). */
    @GetMapping("/api/news/{id}/history")
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> getNewsHistory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        if (privateNewsIsHidden(id, user)) {
            return notFound();
        }
        HistoryPayloadGuard.enforceFullResponseCharacters(
                newsHistoryRepository.totalContentCharactersByNewsId(id));
        List<NewsHistory> history = CompleteResultGuard.enforce(
                newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(id, CompleteResultGuard.sentinelPage()));
        Map<Long, String> namesByUserId = userRepository
                .findAllById(history.stream().map(NewsHistory::getUpdatedBy).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getName));

        List<NewsHistoryResponse> rows = history.stream()
                .map(h -> new NewsHistoryResponse(h.getId(), h.getTitle(), h.getContent(), h.getAttachmentUrl(),
                        h.getUpdatedAt(), namesByUserId.get(h.getUpdatedBy())))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** CLOB-free list companion; the legacy full-history wire remains unchanged. */
    @GetMapping("/api/news/{id}/history-summary")
    public ResponseEntity<?> getNewsHistorySummary(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        if (privateNewsIsHidden(id, user)) {
            return notFound();
        }
        List<NewsHistorySummary> history = CompleteResultGuard.enforce(
                newsHistoryRepository.findSummaryByNewsIdOrderByUpdatedAtDesc(
                        id, CompleteResultGuard.sentinelPage()));
        Map<Long, String> namesByUserId = userRepository
                .findAllById(history.stream().map(NewsHistorySummary::updatedBy).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getName));

        List<NewsHistorySummaryResponse> rows = history.stream()
                .map(h -> new NewsHistorySummaryResponse(h.id(), h.title(), h.attachmentUrl(),
                        h.updatedAt(), namesByUserId.get(h.updatedBy())))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** Loads one news content CLOB after a summary row is selected. */
    @GetMapping("/api/news/{id}/history/{historyId}")
    public ResponseEntity<?> getNewsHistoryItem(
            @PathVariable Long id, @PathVariable Long historyId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        if (privateNewsIsHidden(id, user)) {
            return notFound();
        }
        Optional<NewsHistory> history = newsHistoryRepository.findByIdAndNewsId(historyId, id);
        if (history.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        NewsHistory row = history.get();
        String authorName = userRepository.findById(row.getUpdatedBy()).map(User::getName).orElse(null);
        return ResponseEntity.ok(new NewsHistoryResponse(
                row.getId(), row.getTitle(), row.getContent(), row.getAttachmentUrl(),
                row.getUpdatedAt(), authorName));
    }

    /** Port of restore_news_version (routers/news.py:268-302). */
    @PostMapping("/api/news/{id}/history/{historyId}/restore")
    @Transactional
    public ResponseEntity<?> restoreNewsVersion(
            @PathVariable Long id, @PathVariable("historyId") Long historyId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!PrivateDraftAccess.canAccess(news.isDraft(), news.getAuthorId(), user)) {
            return notFound();
        }
        Map<String, Object> before = MutationAuditService.newsSnapshot(news);

        Optional<NewsHistory> historyRow = newsHistoryRepository.findByIdAndNewsId(historyId, id);
        if (historyRow.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        NewsHistory h = historyRow.get();

        archiveCurrentState(news, user.getId());

        news.setTitle(h.getTitle());
        news.setContent(articleHtmlSanitizer.sanitize(h.getContent()));
        news.setAttachmentUrl(h.getAttachmentUrl());
        news.setVersion(news.getVersion() + 1);

        News saved = newsRepository.saveAndFlush(news);
        searchReindexService.reindexNews(saved);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("news", saved.getId(), saved.getContent(), saved.getAttachmentUrl());
        contentMutationAuditService.recordSuccess(
                user, "RESTORE_VERSION", "news", saved.getId(), saved.getTitle(), before,
                MutationAuditService.newsSnapshot(saved));
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    private void applySharedFields(News news, NewsRequest request) {
        news.setTitle(request.title());
        news.setContent(articleHtmlSanitizer.sanitize(request.content()));
        news.setTargetDepartment(request.targetDepartmentOrDefault());
        news.setAttachmentUrl(request.attachmentUrl());
        news.setVisibleToTechInfo(request.visibleToTechInfoOrDefault());
        news.setVisibleToServiceCenter(request.visibleToServiceCenterOrDefault());
    }

    private void archiveCurrentState(News news, Long updatedBy) {
        NewsHistory history = new NewsHistory();
        history.setNewsId(news.getId());
        history.setTitle(news.getTitle());
        history.setContent(news.getContent());
        history.setAttachmentUrl(news.getAttachmentUrl());
        history.setUpdatedBy(updatedBy);
        history.setUpdatedAt(TbilisiTime.now());
        newsHistoryRepository.save(history);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    /** Preserve empty history for absent IDs without exposing existing private content. */
    private boolean privateNewsIsHidden(Long id, User user) {
        return newsRepository.findById(id)
                .map(item -> !PrivateDraftAccess.canAccess(item.isDraft(), item.getAuthorId(), user))
                .orElseGet(() -> newsRepository.countInaccessiblePrivateDraftIncludingTrash(id, user.getId()) > 0);
    }

}
