package ge.magti.portal.web;

import ge.magti.portal.article.ArticleEvidenceCardinalityGuard;
import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.compliance.ReadingAcknowledgementService;
import ge.magti.portal.article.ArticleViewQueryService;
import ge.magti.portal.article.EligibleOperatorsService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleReadReceipt;
import ge.magti.portal.domain.ArticleViewLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.quiz.QuizGateChecker;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.security.Scope;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;
import static ge.magti.portal.web.ArticleEndpointSupport.assertArticleVisible;

/**
 * Who read an article and who opened it: read receipts (the list, a
 * reader's own receipt and its status) and view tracking (recording an open,
 * and the system administrator's view log). Part of the article API split
 * described on {@link ArticleController}.
 */
@RestController
public class ArticleReadTrackingController {

    private final ArticleRepository articleRepository;
    private final ArticleReadReceiptRepository articleReadReceiptRepository;
    private final ArticleViewLogRepository articleViewLogRepository;
    private final UserRepository userRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadingAcknowledgementService readingAcknowledgementService;
    private final QuizGateChecker quizGateChecker;
    private final PermissionChecker permissionChecker;
    private final ScopeResolver scopeResolver;
    private final ArticleViewQueryService articleViewQueryService;
    private final EligibleOperatorsService eligibleOperatorsService;
    private final ArticleEndpointSupport articleSupport;

    public ArticleReadTrackingController(
            ArticleRepository articleRepository,
            ArticleReadReceiptRepository articleReadReceiptRepository,
            ArticleViewLogRepository articleViewLogRepository,
            UserRepository userRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadingAcknowledgementService readingAcknowledgementService,
            QuizGateChecker quizGateChecker,
            PermissionChecker permissionChecker,
            ScopeResolver scopeResolver,
            ArticleViewQueryService articleViewQueryService,
            EligibleOperatorsService eligibleOperatorsService,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.articleReadReceiptRepository = articleReadReceiptRepository;
        this.articleViewLogRepository = articleViewLogRepository;
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readingAcknowledgementService = readingAcknowledgementService;
        this.quizGateChecker = quizGateChecker;
        this.permissionChecker = permissionChecker;
        this.scopeResolver = scopeResolver;
        this.articleViewQueryService = articleViewQueryService;
        this.eligibleOperatorsService = eligibleOperatorsService;
        this.articleSupport = articleSupport;
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
        if (ArticleVisibility.isPrivateDraftOfAnother(article, user)) {
            return notFound();
        }

        OffsetDateTime dueDate = requiredReadingRepository.findFirstByItemTypeAndItemId("article", id)
                .map(RequiredReading::getDueDate).orElse(null);
        int targetVersion = version != null ? version : article.getVersion();
        List<User> eligibleUsers = eligibleOperatorsService.forArticle(article, articleSupport.resolveTargetDepartments(id));
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
        // shows, from its own frozen snapshot.
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
        ResponseEntity<Map<String, String>> quizGate = requireQuizPassed(article, user);
        if (quizGate != null) {
            return quizGate;
        }

        // Compliance bridge: prefix-aware,
        // like EligibleOperatorsService since 2026-10-01 -- this one
        // reuses the same [dept, deptPrefix, "All"] pattern the article
        // list's own query uses. Only fills gaps: an already-"read"
        // ReadStatus keeps its original read_at.
        List<RequiredReading> covering = CompleteResultGuard.enforce(
                requiredReadingRepository.findByItemTypeAndItemIdAndTargetDepartmentIn(
                        "article", id, DepartmentMatcher.visibilityTargets(user.getDepartment()),
                        CompleteResultGuard.sentinelPage()));
        ArticleReadReceipt receipt = readingAcknowledgementService.acknowledgeArticle(article, user, covering);

        return ResponseEntity.ok(new CreateReadReceiptResponse("success", receipt.getReadAt(), receipt.getArticleVersion()));
    }

    @GetMapping("/api/articles/{id}/read-receipt/me")
    public ResponseEntity<?> getMyArticleReadReceiptStatus(@PathVariable Long id, @AuthenticationPrincipal User user) {
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
        if (ArticleVisibility.isPrivateDraftOfAnother(article, user)) {
            return notFound();
        }

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

    /** Delegated to the shared {@link QuizGateChecker}, which ComplianceController's mark-read reuses too. */
    private ResponseEntity<Map<String, String>> requireQuizPassed(Article article, User user) {
        return quizGateChecker.denialFor(article, user);
    }

    private ResponseEntity<Map<String, String>> requireReadEvidenceAccess(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
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
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
