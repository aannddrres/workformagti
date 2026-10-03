package ge.magti.portal.web;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;

/**
 * An article's lifecycle: delete (to the trash), archive, unarchive and the
 * three bulk operations. Part of the article API split described on
 * {@link ArticleController}.
 */
@RestController
public class ArticleLifecycleController {

    /**
     * One transaction per article in a bulk operation (PO-53). Every audited
     * write takes the audit chain's single lock (V28) until it commits, so a
     * bulk change of 100 articles in one transaction held every sign-in and
     * confirmation in the company for its whole length -- 4 s in QA round 5,
     * and a pool-draining outage for anything longer. REQUIRED, not
     * REQUIRES_NEW: inside a caller's transaction (the rolled-back model
     * tests) the work joins it instead of committing around it. Optional for
     * the same reason as ArticleEndpointSupport's departmentTargets.
     */
    private org.springframework.transaction.support.TransactionTemplate perArticle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.perArticle = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    private boolean inItsOwnTransaction(java.util.function.Supplier<Boolean> work) {
        return perArticle == null ? work.get() : Boolean.TRUE.equals(perArticle.execute(status -> work.get()));
    }

    // Still named for ArticleController, where the bulk operations lived
    // before the split, so log filters and alerts on that name keep matching.
    private static final org.slf4j.Logger bulkLog = org.slf4j.LoggerFactory.getLogger(ArticleController.class);

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final SearchReindexService searchReindexService;
    private final ContentLifecycleService contentLifecycleService;
    private final MutationAuditService contentMutationAuditService;
    /** PO-40: a bulk re-aim extends a mandatory article's obligation to the departments it adds. */
    private final ComplianceController complianceController;
    private final ArticleEndpointSupport articleSupport;

    public ArticleLifecycleController(
            ArticleRepository articleRepository,
            CategoryRepository categoryRepository,
            RequiredReadingRepository requiredReadingRepository,
            SearchReindexService searchReindexService,
            ContentLifecycleService contentLifecycleService,
            MutationAuditService contentMutationAuditService,
            ComplianceController complianceController,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.searchReindexService = searchReindexService;
        this.contentLifecycleService = contentLifecycleService;
        this.contentMutationAuditService = contentMutationAuditService;
        this.complianceController = complianceController;
        this.articleSupport = articleSupport;
    }

    @DeleteMapping("/api/articles/{id}")
    @Transactional
    public ResponseEntity<?> deleteArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty() || ArticleVisibility.isPrivateDraftOfAnother(found.get(), user)) {
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
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesArchivePermission(user);
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
        if ("archived".equals(article.getStatus())) {
            return ResponseEntity.ok(ArticleResponse.from(article, articleSupport.resolveTargetDepartments(id)));
        }

        List<String> targetDepartments = articleSupport.resolveTargetDepartments(id);
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
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesArchivePermission(user);
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
        if (!"archived".equals(article.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "სტატია არ არის არქივში"));
        }

        List<String> targetDepartments = articleSupport.resolveTargetDepartments(id);
        Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);
        // PO-54: back to where it was archived from, not "published" -- an
        // archived draft or next week's article must not go live here.
        article.setStatus(article.statusAfterArchive(TbilisiTime.now()));
        article.setUpdatedAt(TbilisiTime.now());
        Article saved = articleRepository.saveAndFlush(article);
        contentMutationAuditService.recordSuccess(
                user, "UNARCHIVE", "article", saved.getId(), saved.getTitle(), before,
                MutationAuditService.articleSnapshot(saved, targetDepartments));
        return ResponseEntity.ok(ArticleResponse.from(saved, targetDepartments));
    }

    /**
     * The original two-state bulk operation, kept because it is in the API
     * contract and the golden master. It delegates rather than duplicating:
     * two implementations of "move these articles to that status" would
     * eventually audit differently, and the audit trail is the point.
     */
    @PostMapping("/api/articles/bulk-archive")
    public ResponseEntity<?> bulkArchiveArticles(
            @Valid @RequestBody ArticleBulkArchiveRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesArchivePermission(user);
        if (denial != null) {
            return denial;
        }
        // "published" stays the wire answer for an unarchive; each article
        // itself goes back to where it was archived from (PO-54).
        String target = request.archive() ? "archived" : "published";
        BulkStatusOutcome outcome = applyBulkStatus(request.ids(), request.archive() ? "archived" : RESTORE, user);
        return ResponseEntity.ok(
                new ArticleBulkArchiveResponse(outcome.updated(), target, outcome.skipped()));
    }

    /**
     * Move many articles to one status (draft, published or archived).
     *
     * <p>Exists for the legacy import: 122 articles arrive as drafts and are
     * released a few at a time, some later as mandatory reading. Doing that
     * through the single-article drawer is 122 round trips through a modal.
     *
     * <p>Gated on {@code articles.archive} rather than {@code articles.edit},
     * matching bulk-archive. Every status here is a publication decision --
     * who can see this, from when -- not a change to what the article says,
     * and the archive permission is the one the access contract already
     * attaches to that question.
     */
    @PostMapping("/api/articles/bulk-status")
    public ResponseEntity<?> bulkSetArticleStatus(
            @Valid @RequestBody ArticleBulkStatusRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesArchivePermission(user);
        if (denial != null) {
            return denial;
        }
        BulkStatusOutcome outcome = applyBulkStatus(request.ids(), request.status(), user);
        return ResponseEntity.ok(new ArticleBulkResponse(outcome.updated(), outcome.skipped()));
    }

    /**
     * Re-file many articles into a different category, a different audience,
     * or both.
     *
     * <p>Gated on {@code articles.edit}, not archive: changing who an article
     * is aimed at changes the article, and it is the one bulk operation that
     * can make content reach people it was never written for.
     */
    @PostMapping("/api/articles/bulk-retarget")
    public ResponseEntity<?> bulkRetargetArticles(
            @Valid @RequestBody ArticleBulkRetargetRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = articleSupport.requireArticlesEditPermission(user);
        if (denial != null) {
            return denial;
        }
        if (request.changesNothing()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("detail", "მიუთითეთ კატეგორია ან დეპარტამენტები"));
        }
        if (request.targetDepartments() != null) {
            ResponseEntity<Map<String, String>> unknownTargets = articleSupport.unknownDepartments(request.targetDepartments());
            if (unknownTargets != null) {
                return unknownTargets;
            }
        }
        if (request.targetDepartments() != null && request.targetDepartments().isEmpty()) {
            // An empty list would leave the articles addressed to nobody, which
            // reads as "hidden" and is not what an audience field means. Use
            // bulk-status to hide something.
            return ResponseEntity.badRequest()
                    .body(Map.of("detail", "დეპარტამენტების სია ცარიელი ვერ იქნება"));
        }
        if (request.categoryId() != null && !categoryRepository.existsById(request.categoryId())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "კატეგორია ვერ მოიძებნა"));
        }

        List<Article> rows = changeableBy(articleRepository.findAllById(request.ids()), user);
        Set<Long> found = rows.stream().map(Article::getId).collect(Collectors.toSet());
        List<Long> skipped = new ArrayList<>();
        for (Long requestedId : request.ids()) {
            if (!found.contains(requestedId)) {
                skipped.add(requestedId);
            }
        }

        int updated = 0;
        for (Article listed : rows) {
            boolean done = applyToOne(listed.getId(), skipped, article -> {
                List<String> before = articleSupport.resolveTargetDepartments(article.getId());
                Map<String, Object> snapshot = MutationAuditService.articleSnapshot(article, before);

                if (request.categoryId() != null) {
                    article.setCategoryId(request.categoryId());
                }
                article.setUpdatedAt(TbilisiTime.now());
                Article saved = articleRepository.saveAndFlush(article);

                List<String> after = before;
                if (request.targetDepartments() != null) {
                    after = request.targetDepartments().stream().distinct().toList();
                    articleSupport.replaceTargetDepartments(saved.getId(), after);
                    extendMandatoryToAudience(saved.getId(), after, user);
                }

                contentMutationAuditService.recordSuccess(
                        user, "UPDATE", "article", saved.getId(), saved.getTitle(),
                        snapshot, MutationAuditService.articleSnapshot(saved, after));
                return true;
            });
            if (done) {
                updated++;
            }
        }

        return ResponseEntity.ok(new ArticleBulkResponse(updated, skipped));
    }

    /**
     * PO-40: a mandatory article binds everyone its audience covers, so a
     * department a bulk re-aim adds gets its own reading, with the due date
     * and priority the article's obligation already has -- through the one
     * create path, with its checks, reminders and audit. One that path refuses
     * because nobody can open the article now (archived, unpublished, or
     * scheduled past that due date) is kept out of force instead, and comes
     * into force with the article (owner, 2026-10-02). It used to be skipped
     * "until the editor's next mandatory save", which a restore never is:
     * RoleFlowModelIntegrationTest found the new department owing nothing
     * after the article came back. A department the re-aim drops keeps its
     * reading and its confirmations, out of force.
     */
    private void extendMandatoryToAudience(Long articleId, List<String> audience, User user) {
        List<RequiredReading> existing = requiredReadingRepository.findByItemTypeAndItemId("article", articleId);
        if (existing.isEmpty()) {
            return;
        }
        RequiredReading model = existing.get(0);
        Set<String> targeted = existing.stream().map(RequiredReading::getTargetDepartment).collect(Collectors.toSet());
        for (String target : MandatoryReach.readingTargets(audience)) {
            if (!targeted.contains(target)) {
                RequiredReadingRequest request = new RequiredReadingRequest(
                        "article", articleId, target, model.getDueDate(), model.getPriority());
                if (!complianceController.createRequiredReading(request, user).getStatusCode().is2xxSuccessful()) {
                    complianceController.addReadingOutOfForce(request, user);
                }
            }
        }
    }

    /** What one bulk status change did, before it is shaped into a response. */
    private record BulkStatusOutcome(int updated, List<Long> skipped) {
    }

    /**
     * Another author's private draft is reported as skipped, exactly like an
     * id that does not exist. bulk-status clears {@code is_draft}, so without
     * this it published a colleague's autosave to its whole audience.
     */
    private static List<Article> changeableBy(List<Article> rows, User user) {
        return rows.stream().filter(a -> !ArticleVisibility.isPrivateDraftOfAnother(a, user)).toList();
    }

    /**
     * The single implementation behind both status endpoints.
     *
     * <p>Audits each article individually with its own before/after snapshot
     * rather than recording "50 articles archived". A compliance question is
     * always about one article -- when did this stop being visible, and who
     * decided -- and a batch row cannot answer it.
     */
    private BulkStatusOutcome applyBulkStatus(List<Long> ids, String target, User user) {
        List<Article> rows = changeableBy(articleRepository.findAllById(ids), user);
        Set<Long> found = rows.stream().map(Article::getId).collect(Collectors.toSet());
        List<Long> skipped = new ArrayList<>();
        for (Long requestedId : ids) {
            if (!found.contains(requestedId)) {
                skipped.add(requestedId);
            }
        }

        String action = switch (target) {
            case "archived" -> "ARCHIVE";
            case "published", RESTORE -> "UNARCHIVE";
            default -> "UPDATE";
        };

        int updated = 0;
        for (Article listed : rows) {
            boolean done = applyToOne(listed.getId(), skipped, article -> {
                if (RESTORE.equals(target) ? !"archived".equals(article.getStatus())
                        : target.equals(article.getStatus())) {
                    return false;
                }
                List<String> targetDepartments = articleSupport.resolveTargetDepartments(article.getId());
                Map<String, Object> before = MutationAuditService.articleSnapshot(article, targetDepartments);

                // PO-54: an unarchive goes back to the archived-from state.
                String next = RESTORE.equals(target) ? article.statusAfterArchive(TbilisiTime.now()) : target;
                article.setStatus(next);
                // is_draft is the personal-autosave flag, and GET /api/articles
                // hides a row with it set from everyone but its author. Publishing
                // or archiving an article that still carried it would leave it
                // invisible to the very people the status change was for.
                article.setDraft(false);
                if ("published".equals(next) && article.getPublishedAt() == null) {
                    article.setPublishedAt(TbilisiTime.now());
                }
                article.setUpdatedAt(TbilisiTime.now());
                Article saved = articleRepository.saveAndFlush(article);

                contentMutationAuditService.recordSuccess(
                        user, action, "article", saved.getId(), saved.getTitle(), before,
                        MutationAuditService.articleSnapshot(saved, targetDepartments));
                return true;
            });
            if (done) {
                updated++;
            }
        }
        return new BulkStatusOutcome(updated, skipped);
    }

    /** applyBulkStatus's target for "take it out of the archive", whatever it was before (PO-54). */
    private static final String RESTORE = "restore-from-archive";

    /**
     * One article of a bulk operation, in its own transaction (PO-53), read
     * afresh inside it. False -- and the id reported as skipped -- when the
     * work declines it or fails; a failure no longer undoes the articles
     * already done (owner, 2026-10-03: a partly finished bulk is acceptable,
     * holding everyone's audit writes for its whole length is not).
     */
    private boolean applyToOne(Long id, List<Long> skipped,
                               java.util.function.Function<Article, Boolean> work) {
        boolean done;
        try {
            done = inItsOwnTransaction(() -> articleRepository.findById(id).map(work).orElse(false));
        } catch (RuntimeException e) {
            bulkLog.warn("Bulk operation skipped article {} after {}", id, e.getClass().getSimpleName());
            done = false;
        }
        if (!done) {
            skipped.add(id);
        }
        return done;
    }
}
