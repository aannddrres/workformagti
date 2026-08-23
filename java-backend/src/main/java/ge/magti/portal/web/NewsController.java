package ge.magti.portal.web;

import ge.magti.portal.content.ContentSanitizer;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.NewsHistory;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.news.NewsQueryService;
import ge.magti.portal.repository.NewsHistoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
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
 * <p><b>Known, deliberate gaps, same reasoning as every other Content
 * controller so far:</b> no automatic ORM-listener audit row (Python's own
 * update_news/delete_news don't call log_audit either -- only
 * restore_news_version does, ported below); no search_cache clearing (no
 * such cache exists yet in the Java port); no SSE broadcast (_notify --
 * Messaging domain isn't built yet).
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
    private final AuditLogRepository auditLogRepository;

    public NewsController(
            NewsRepository newsRepository,
            NewsHistoryRepository newsHistoryRepository,
            UserRepository userRepository,
            NewsQueryService newsQueryService,
            SearchReindexService searchReindexService,
            ContentLifecycleService contentLifecycleService,
            PermissionChecker permissionChecker,
            AuditLogRepository auditLogRepository) {
        this.newsRepository = newsRepository;
        this.newsHistoryRepository = newsHistoryRepository;
        this.userRepository = userRepository;
        this.newsQueryService = newsQueryService;
        this.searchReindexService = searchReindexService;
        this.contentLifecycleService = contentLifecycleService;
        this.permissionChecker = permissionChecker;
        this.auditLogRepository = auditLogRepository;
    }

    /** Port of get_news_item (routers/news.py:23-44). */
    @GetMapping("/api/news/{id}")
    public ResponseEntity<?> getNewsItem(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();

        if (user.getRole().isContentAdmin()) {
            if (news.isDraft() && !java.util.Objects.equals(news.getAuthorId(), user.getId())) {
                return notFound();
            }
        } else {
            if (news.isDraft()) {
                return notFound();
            }
            if (!DepartmentMatcher.matches(user.getDepartment(), List.of(news.getTargetDepartment()))) {
                return notFound();
            }
        }
        return ResponseEntity.ok(NewsResponse.from(news));
    }

    /** Port of get_news (routers/news.py:47-102). */
    @GetMapping("/api/news")
    public ResponseEntity<?> getNews(
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
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
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        News news = new News();
        applySharedFields(news, request);
        news.setExpiresAt(request.expiresAt());
        news.setDraft(request.isDraftOrDefaultForCreate());
        news.setAuthorId(user.getId());
        news.setCreatedAt(TbilisiTime.now());
        News saved = newsRepository.save(news);
        searchReindexService.reindexNews(saved);
        return ResponseEntity.status(HttpStatus.OK).body(NewsResponse.from(saved));
    }

    /** Port of update_news (routers/news.py:141-183). */
    @PutMapping("/api/news/{id}")
    @Transactional
    public ResponseEntity<?> updateNews(
            @PathVariable Long id, @Valid @RequestBody NewsRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();

        archiveCurrentState(news, user.getId());

        applySharedFields(news, request);
        // author_id/isDraft/expiresAt deliberately NOT touched here -- see
        // NewsRequest's javadoc: the edit form doesn't manage any of the
        // three, so a full-replace (Python's literal behaviour) would
        // silently null the author and un-publish/clear-expiry on every
        // unrelated edit. Preserved from the existing row instead.
        news.setVersion(news.getVersion() + 1);

        News saved = newsRepository.save(news);
        searchReindexService.reindexNews(saved);
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    /** Port of delete_news (routers/news.py:186-213). */
    @DeleteMapping("/api/news/{id}")
    @Transactional
    public ResponseEntity<?> deleteNews(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
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
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!news.isArchived()) {
            news.setExpiresAt(TbilisiTime.now());
            newsRepository.save(news);
            writeAuditLog(user, "ARCHIVE", news);
        }
        searchReindexService.remove(SearchReindexService.NEWS, id);
        return ResponseEntity.ok(NewsResponse.from(news));
    }

    @PostMapping("/api/news/{id}/unarchive")
    @Transactional
    public ResponseEntity<?> unarchiveNews(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();
        if (!news.isArchived()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "სიახლე არ არის არქივში"));
        }
        news.setExpiresAt(null);
        News saved = newsRepository.save(news);
        writeAuditLog(user, "UNARCHIVE", saved);
        searchReindexService.reindexNews(saved);
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    /** Port of autosave_news (routers/news.py:216-238). */
    @PatchMapping("/api/news/{id}/autosave")
    @Transactional
    public ResponseEntity<?> autosaveNews(
            @PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();

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
            news.setContent(ContentSanitizer.sanitize((String) body.get("content")));
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
        searchReindexService.reindexNews(saved);
        return ResponseEntity.ok(NewsAutosaveResponse.from(saved));
    }

    /** Port of get_news_history (routers/news.py:241-265). */
    @GetMapping("/api/news/{id}/history")
    public ResponseEntity<?> getNewsHistory(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        List<NewsHistory> history = newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(id);
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

    /** Port of restore_news_version (routers/news.py:268-302). */
    @PostMapping("/api/news/{id}/history/{historyId}/restore")
    @Transactional
    public ResponseEntity<?> restoreNewsVersion(
            @PathVariable Long id, @PathVariable("historyId") Long historyId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        Optional<News> found = newsRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        News news = found.get();

        Optional<NewsHistory> historyRow = newsHistoryRepository.findByIdAndNewsId(historyId, id);
        if (historyRow.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ისტორიის ვერსია ვერ მოიძებნა"));
        }
        NewsHistory h = historyRow.get();

        archiveCurrentState(news, user.getId());

        news.setTitle(h.getTitle());
        news.setContent(h.getContent());
        news.setAttachmentUrl(h.getAttachmentUrl());
        news.setVersion(news.getVersion() + 1);

        News saved = newsRepository.save(news);
        searchReindexService.reindexNews(saved);
        writeAuditLog(user, "RESTORE_VERSION", saved);
        return ResponseEntity.ok(NewsResponse.from(saved));
    }

    private static void applySharedFields(News news, NewsRequest request) {
        news.setTitle(request.title());
        news.setContent(ContentSanitizer.sanitize(request.content()));
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

    private void writeAuditLog(User actor, String action, News news) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(actor.getId());
        audit.setAdminNameSnapshot(actor.getName());
        audit.setAdminEmailSnapshot(actor.getEmail());
        audit.setAction(action);
        audit.setItemType("news");
        audit.setItemId(news.getId());
        audit.setItemNameSnapshot(news.getTitle());
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
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
}
