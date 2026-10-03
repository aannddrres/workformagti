package ge.magti.portal.web;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.security.PermissionChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * What more than one article controller needs, written once: the not-found
 * answer, the visibility check, an article's audience (read and replace),
 * the unknown-department refusal and the two article permission guards
 * ({@code articles.edit}, {@code articles.archive}).
 *
 * <p>These were private helpers of a single ArticleController before it was
 * split by responsibility (see {@link ArticleController}). They moved here
 * unchanged rather than being copied into each new controller, so each rule
 * still has one implementation.
 *
 * <p>Not a controller and not an authorization layer of its own: every
 * handler still calls its guard itself, which is what
 * {@code EndpointGuardCoverageTest} and {@code AccessContractCoverageTest}
 * read.
 */
@Component
class ArticleEndpointSupport {

    private static final String NOT_FOUND_DETAIL = "სტატია ვერ მოიძებნა";

    /** Optional so the DB-free test constructions need no change; Spring always sets it. */
    private ge.magti.portal.org.DepartmentTargets departmentTargets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepartmentTargets(ge.magti.portal.org.DepartmentTargets departmentTargets) {
        this.departmentTargets = departmentTargets;
    }

    /** A 422 naming any audience department that reaches nobody (simulation, 2026-10-01). */
    ResponseEntity<Map<String, String>> unknownDepartments(java.util.Collection<String> targets) {
        return departmentTargets == null ? null : departmentTargets.refusal(targets);
    }

    private final ArticleTargetDepartmentRepository targetDepartmentRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final PermissionChecker permissionChecker;

    ArticleEndpointSupport(
            ArticleTargetDepartmentRepository targetDepartmentRepository,
            ArticleTargetQueryService articleTargetQueryService,
            PermissionChecker permissionChecker) {
        this.targetDepartmentRepository = targetDepartmentRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.permissionChecker = permissionChecker;
    }

    List<String> resolveTargetDepartments(Long articleId) {
        return articleTargetQueryService.targetDepartmentsForArticleWithinLimit(articleId);
    }

    void replaceTargetDepartments(Long articleId, List<String> departments) {
        targetDepartmentRepository.deleteByArticleId(articleId);
        for (String department : departments) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(articleId);
            row.setDepartment(department);
            targetDepartmentRepository.save(row);
        }
    }

    static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    /**
     * The predicate itself now lives in {@link ArticleVisibility}, because
     * {@code /uploads/{filename}} needs the same answer before serving a file
     * that an article carries (DEC-P01). This wrapper keeps the 404-shaped
     * response every caller in here already expects.
     */
    static ResponseEntity<Map<String, String>> assertArticleVisible(
            Article article, List<String> targetDepartments, User user) {
        return ArticleVisibility.isVisible(article, targetDepartments, user) ? null : notFoundMap();
    }

    static ResponseEntity<Map<String, String>> notFoundMap() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    ResponseEntity<Map<String, String>> requireArticlesArchivePermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
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
     * but no endpoint ever consulted them. Revoking a content_admin's
     * articles.edit did nothing; the permission editor was lying. Now
     * actually enforced on the 4 mutating endpoints, same {@link
     * PermissionChecker} pattern as {@link #requireArticlesArchivePermission}.
     */
    ResponseEntity<Map<String, String>> requireArticlesEditPermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
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
