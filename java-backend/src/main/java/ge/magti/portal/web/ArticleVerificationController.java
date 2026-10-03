package ge.magti.portal.web;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ge.magti.portal.web.ArticleEndpointSupport.notFound;

/**
 * Keeping articles current: marking one verified and the report of
 * published articles nobody has verified for 180 days. Part of the article
 * API split described on {@link ArticleController}.
 */
@RestController
public class ArticleVerificationController {

    private final ArticleRepository articleRepository;
    private final PermissionChecker permissionChecker;
    private final MutationAuditService contentMutationAuditService;
    private final ArticleEndpointSupport articleSupport;

    public ArticleVerificationController(
            ArticleRepository articleRepository,
            PermissionChecker permissionChecker,
            MutationAuditService contentMutationAuditService,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.permissionChecker = permissionChecker;
        this.contentMutationAuditService = contentMutationAuditService;
        this.articleSupport = articleSupport;
    }

    @PostMapping("/api/articles/{id}/verify")
    @Transactional
    public ResponseEntity<?> verifyArticle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
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
        List<String> targetDepartments = articleSupport.resolveTargetDepartments(id);
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
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        OffsetDateTime cutoff = TbilisiTime.now().minusDays(180);
        List<StaleArticleResponse> stale = CompleteResultGuard.enforce(articleRepository
                .findStaleReferences("published", cutoff, user.getId(), CompleteResultGuard.sentinelPage())).stream()
                .map(a -> new StaleArticleResponse(
                        a.id(), a.title(), articleSupport.resolveTargetDepartments(a.id()), a.lastVerifiedAt(),
                        Duration.between(a.lastVerifiedAt(), TbilisiTime.now()).toDays()))
                .toList();
        return ResponseEntity.ok(stale);
    }
}
