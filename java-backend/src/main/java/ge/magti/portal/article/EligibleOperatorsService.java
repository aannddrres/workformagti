package ge.magti.portal.article;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Port of _get_eligible_operators (routers/articles.py:971-1003) -- who
 * the article's read-receipts view treats as "supposed to read this
 * article."
 *
 * <p>Prefix-aware since 2026-10-01: the same {@link DepartmentMatcher} rule
 * that lets a person open the article. It was ported as an exact match
 * (Python's {@code User.department.in_(target_depts)}, routers/articles.py:993),
 * deliberately, as a second rule beside the reading one. The two disagreed
 * where it matters: an article for "ტექნიკური" reached, and was owed by,
 * everyone in "ტექნიკური — ჯგუფი 03", while its receipts listed only the
 * people stored as the bare department -- the group's confirmations were
 * counted on every other screen and missing from this one
 * (RoleFlowIntegrationTest).
 *
 * <p>Not ported: the {@code tech_info}/{@code service_center} role
 * exclusion branches (routers/articles.py:997-1000) -- unreachable dead
 * code, same finding as {@link ge.magti.portal.article.ArticleQueryService}'s
 * own javadoc explains for the identical branches in get_articles'
 * "Block 5". {@code Role} has no such values because no user can ever
 * hold them through any validated path.
 */
@Service
public class EligibleOperatorsService {

    private final UserDirectoryQueryService userDirectoryQueryService;

    public EligibleOperatorsService(UserDirectoryQueryService userDirectoryQueryService) {
        this.userDirectoryQueryService = userDirectoryQueryService;
    }

    public List<User> forArticle(Article article, List<String> targetDepartments) {
        if (article.isDraft()) {
            return List.of();
        }
        boolean isVisible = "published".equals(article.getStatus())
                || ("scheduled".equals(article.getStatus()) && article.getPublishedAt() != null
                        && !article.getPublishedAt().isAfter(TbilisiTime.now()));
        if (!isVisible) {
            return List.of();
        }

        return userDirectoryQueryService.listActiveUsersWithinLimit().stream()
                .filter(u -> !ComplianceCalculator.MANAGEMENT_ROLES.contains(u.getRole()))
                .filter(u -> DepartmentMatcher.matches(u.getDepartment(), targetDepartments))
                .toList();
    }
}
