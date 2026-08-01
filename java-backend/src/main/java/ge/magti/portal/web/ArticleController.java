package ge.magti.portal.web;

import ge.magti.portal.article.ArticleListFilter;
import ge.magti.portal.article.ArticleQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleHistory;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserNote;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserNoteRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.TagSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.time.Duration;
import java.time.OffsetDateTime;
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

/**
 * Mirrors routers/articles.py -- core CRUD + lifecycle only (list, get,
 * create, update, autosave, delete, archive, unarchive, bulk-archive). The
 * remaining 23 endpoints (history/diff/restore, quiz, knowledge-score/
 * leaderboard, read-receipts/views, notes/verify/stale/related/deprecated-
 * feedback) are later, separate slices -- this domain is too large to port
 * in one HTTP surface the way Videos/Categories were.
 *
 * <p>Same two-gate shape as {@link VideoController}/{@link
 * CategoryController}: no/invalid token (401, English), wrong role for
 * create/update/autosave/delete (403, English -- {@code
 * get_current_admin_user}), missing the granular {@code articles.archive}
 * permission for archive/unarchive/bulk-archive (403, Georgian -- {@code
 * require_permission}).
 *
 * <p><b>Known, deliberate gaps, same reasoning as Videos/Categories:</b> no
 * automatic ORM-listener audit row on create/update/delete/autosave (only
 * archive/unarchive/bulk-archive write one explicitly, matching exactly
 * what routers/articles.py's own code does); no TTL cache clearing
 * (search_cache/category_cache); no SSE broadcast (_notify/_notify_revision)
 * -- none of that infrastructure exists in the Java port yet.
 */
@RestController
public class ArticleController {

    private static final String NOT_FOUND_DETAIL = "სტატია ვერ მოიძებნა";

    private final ArticleRepository articleRepository;
    private final ArticleTargetDepartmentRepository targetDepartmentRepository;
    private final ArticleHistoryRepository articleHistoryRepository;
    private final CategoryRepository categoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserNoteRepository userNoteRepository;
    private final PermissionChecker permissionChecker;
    private final TagSyncService tagSyncService;
    private final ArticleQueryService articleQueryService;

    public ArticleController(
            ArticleRepository articleRepository,
            ArticleTargetDepartmentRepository targetDepartmentRepository,
            ArticleHistoryRepository articleHistoryRepository,
            CategoryRepository categoryRepository,
            AuditLogRepository auditLogRepository,
            UserNoteRepository userNoteRepository,
            PermissionChecker permissionChecker,
            TagSyncService tagSyncService,
            ArticleQueryService articleQueryService) {
        this.articleRepository = articleRepository;
        this.targetDepartmentRepository = targetDepartmentRepository;
        this.articleHistoryRepository = articleHistoryRepository;
        this.categoryRepository = categoryRepository;
        this.auditLogRepository = auditLogRepository;
        this.userNoteRepository = userNoteRepository;
        this.permissionChecker = permissionChecker;
        this.tagSyncService = tagSyncService;
        this.articleQueryService = articleQueryService;
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

        List<Article> articles = articleQueryService.listVisible(
                new ArticleListFilter(q, categoryId, status), user, skip, limit);

        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        Set<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle = targetDepartmentRepository.findByArticleIdIn(articleIds).stream()
                .collect(Collectors.groupingBy(ArticleTargetDepartment::getArticleId,
                        Collectors.mapping(ArticleTargetDepartment::getDepartment, Collectors.toList())));

        List<ArticleSummaryResponse> result = articles.stream()
                .map(a -> ArticleSummaryResponse.from(
                        a, categoryNames.get(a.getCategoryId()), deptsByArticle.getOrDefault(a.getId(), List.of())))
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
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
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

        ArticleHistory history = new ArticleHistory();
        history.setArticleId(saved.getId());
        history.setTitle(saved.getTitle());
        history.setContent(saved.getContent());
        history.setUpdatedBy(user.getId());
        history.setVersionId(1);
        history.setUpdatedAt(saved.getCreatedAt());
        articleHistoryRepository.save(history);

        return ResponseEntity.ok(ArticleResponse.from(saved, request.targetDepartments()));
    }

    @PutMapping("/api/articles/{id}")
    @Transactional
    public ResponseEntity<?> updateArticle(
            @PathVariable Long id, @Valid @RequestBody ArticleRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();

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

        ArticleHistory history = new ArticleHistory();
        history.setArticleId(id);
        history.setTitle(saved.getTitle());
        history.setContent(saved.getContent());
        history.setUpdatedBy(user.getId());
        history.setVersionId(saved.getVersion());
        history.setUpdatedAt(saved.getUpdatedAt());
        articleHistoryRepository.save(history);

        return ResponseEntity.ok(ArticleResponse.from(saved, request.targetDepartments()));
    }

    @PatchMapping("/api/articles/{id}/autosave")
    @Transactional
    public ResponseEntity<?> autosaveArticle(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();

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
            article.setContent((String) body.get("content"));
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

        return ResponseEntity.ok(ArticleAutosaveResponse.from(saved, resolveTargetDepartments(id)));
    }

    @DeleteMapping("/api/articles/{id}")
    @Transactional
    public ResponseEntity<?> deleteArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        articleRepository.delete(found.get());
        return ResponseEntity.noContent().build();
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

        article.setStatus("archived");
        article.setUpdatedAt(TbilisiTime.now());
        writeAuditLog(user.getId(), "ARCHIVE", id);
        Article saved = articleRepository.save(article);
        return ResponseEntity.ok(ArticleResponse.from(saved, resolveTargetDepartments(id)));
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

        article.setStatus("published");
        article.setUpdatedAt(TbilisiTime.now());
        writeAuditLog(user.getId(), "UNARCHIVE", id);
        Article saved = articleRepository.save(article);
        return ResponseEntity.ok(ArticleResponse.from(saved, resolveTargetDepartments(id)));
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
            article.setStatus(target);
            article.setUpdatedAt(TbilisiTime.now());
            // Snapshots set explicitly here, matching routers/articles.py:526-530's
            // own code exactly -- not gap-dependent like the single-item
            // archive/unarchive endpoints above, which rely on Python's
            // automatic ORM-listener audit to fill snapshots (no Java
            // equivalent of that listener exists yet, so those two stay
            // snapshot-less, matching what their own explicit log_audit
            // calls actually pass).
            AuditLog entry = new AuditLog();
            entry.setAdminId(user.getId());
            entry.setAction(request.archive() ? "ARCHIVE" : "UNARCHIVE");
            entry.setItemType("article");
            entry.setItemId(article.getId());
            entry.setTimestamp(TbilisiTime.now());
            entry.setAdminNameSnapshot(user.getName());
            entry.setAdminEmailSnapshot(user.getEmail());
            entry.setItemNameSnapshot(article.getTitle());
            auditLogRepository.save(entry);
            articleRepository.save(article);
            updated++;
        }

        return ResponseEntity.ok(new ArticleBulkArchiveResponse(updated, target, skipped));
    }

    @PostMapping("/api/articles/{id}/feedback")
    public ResponseEntity<?> createArticleFeedback(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("detail", "ხარვეზის რეპორტირება დეპრეკირებულია"));
    }

    @GetMapping("/api/admin/feedback")
    public ResponseEntity<?> getAdminFeedback(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("detail", "უკუკავშირის ნახვა დეპრეკირებულია"));
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
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Article article = found.get();
        article.setLastVerifiedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        writeAuditLog(user.getId(), "VERIFY", id);
        Article saved = articleRepository.save(article);
        return ResponseEntity.ok(ArticleResponse.from(saved, resolveTargetDepartments(id)));
    }

    @GetMapping("/api/admin/articles/stale")
    public ResponseEntity<?> getStaleArticles(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        OffsetDateTime cutoff = TbilisiTime.now().minusDays(180);
        List<StaleArticleResponse> stale = articleRepository
                .findByStatusAndLastVerifiedAtBeforeOrderByLastVerifiedAtAsc("published", cutoff).stream()
                .map(a -> new StaleArticleResponse(
                        a.getId(), a.getTitle(), resolveTargetDepartments(a.getId()), a.getLastVerifiedAt(),
                        Duration.between(a.getLastVerifiedAt(), TbilisiTime.now()).toDays()))
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

        List<Article> published = articleRepository.findByStatus("published").stream()
                .filter(a -> !a.getId().equals(id))
                .toList();
        Set<Long> candidateIds = published.stream().map(Article::getId).collect(Collectors.toSet());
        Map<Long, List<String>> deptsByArticle = targetDepartmentRepository.findByArticleIdIn(candidateIds).stream()
                .collect(Collectors.groupingBy(ArticleTargetDepartment::getArticleId,
                        Collectors.mapping(ArticleTargetDepartment::getDepartment, Collectors.toList())));
        boolean isAdmin = user.getRole().isContentAdmin();
        // Deliberately exact-match + "All" only, NOT DepartmentMatcher's
        // prefix-aware rule -- routers/articles.py:1596-1601 narrows this
        // one candidate filter differently than get_articles' own list
        // query does, and this port carries that difference forward
        // unchanged rather than unifying it.
        List<Article> candidates = published.stream()
                .filter(a -> isAdmin
                        || relatedArticleDeptMatches(user.getDepartment(), deptsByArticle.getOrDefault(a.getId(), List.of())))
                .toList();

        List<Article> results = new ArrayList<>(candidates.stream()
                .filter(a -> Objects.equals(a.getCategoryId(), source.getCategoryId()))
                .limit(4)
                .toList());

        if (results.size() < 4 && source.getTags() != null && !source.getTags().isBlank()) {
            Set<Long> existingIds = new LinkedHashSet<>();
            results.forEach(a -> existingIds.add(a.getId()));
            List<String> tagList = Arrays.stream(source.getTags().split(","))
                    .map(t -> t.strip().toLowerCase())
                    .filter(t -> !t.isEmpty())
                    .toList();
            for (String tag : tagList) {
                if (results.size() >= 4) {
                    break;
                }
                int remaining = 4 - results.size();
                List<Article> tagMatches = candidates.stream()
                        .filter(a -> !existingIds.contains(a.getId()))
                        .filter(a -> a.getTags() != null && a.getTags().toLowerCase().contains(tag))
                        .limit(remaining)
                        .toList();
                for (Article a : tagMatches) {
                    if (existingIds.add(a.getId())) {
                        results.add(a);
                    }
                }
            }
        }

        if (results.size() < 4) {
            Set<Long> existingIds = new LinkedHashSet<>();
            results.forEach(a -> existingIds.add(a.getId()));
            int remaining = 4 - results.size();
            List<Article> fillMatches = candidates.stream()
                    .filter(a -> !existingIds.contains(a.getId()))
                    .sorted(Comparator.comparing(Article::getCreatedAt).reversed())
                    .limit(remaining)
                    .toList();
            for (Article a : fillMatches) {
                if (existingIds.add(a.getId())) {
                    results.add(a);
                }
            }
        }

        List<RelatedArticleResponse> response = results.stream()
                .limit(4)
                .map(a -> new RelatedArticleResponse(a.getId(), a.getTitle(), a.getCategoryId(), a.getTags()))
                .toList();
        return ResponseEntity.ok(response);
    }

    private void applySharedFields(Article article, ArticleRequest request) {
        article.setTitle(request.title());
        article.setContent(request.content());
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
        return targetDepartmentRepository.findByArticleId(articleId).stream()
                .map(ArticleTargetDepartment::getDepartment)
                .toList();
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

    private void writeAuditLog(Long adminId, String action, Long articleId) {
        AuditLog entry = new AuditLog();
        entry.setAdminId(adminId);
        entry.setAction(action);
        entry.setItemType("article");
        entry.setItemId(articleId);
        entry.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(entry);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    /** Port of _assert_article_visible (routers/articles.py:61-94). */
    private static ResponseEntity<Map<String, String>> assertArticleVisible(
            Article article, List<String> targetDepartments, User user) {
        if (user.getRole().isContentAdmin()) {
            return null;
        }
        if (!DepartmentMatcher.matches(user.getDepartment(), targetDepartments)) {
            return notFoundMap();
        }
        if ("published".equals(article.getStatus())) {
            return null;
        }
        if ("scheduled".equals(article.getStatus()) && article.getPublishedAt() != null
                && !article.getPublishedAt().isAfter(TbilisiTime.now())) {
            return null;
        }
        return notFoundMap();
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
}
