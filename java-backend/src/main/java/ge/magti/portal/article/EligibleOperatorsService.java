package ge.magti.portal.article;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Port of _get_eligible_operators (routers/articles.py:971-1003) -- who
 * the read-receipts admin view and the compliance eligibility calculation
 * treat as "supposed to read this article."
 *
 * <p>Deliberately exact-match department filtering, NOT {@link
 * ge.magti.portal.util.DepartmentMatcher}'s prefix-aware rule -- Python's
 * own {@code User.department.in_(target_depts)} (routers/articles.py:993)
 * is a plain IN-list against the raw target department strings, same
 * narrower rule as {@code get_related_articles} uses, and genuinely
 * different from {@code get_articles}'/{@code _assert_article_visible}'s
 * prefix-aware one. Not unified -- carried forward as two different rules
 * in two different places, matching the source.
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

    private final UserRepository userRepository;

    public EligibleOperatorsService(UserRepository userRepository) {
        this.userRepository = userRepository;
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

        List<User> candidates = targetDepartments.contains("All")
                ? userRepository.findByActiveTrue()
                : userRepository.findByActiveTrueAndDepartmentIn(targetDepartments);

        return candidates.stream()
                .filter(u -> !ComplianceCalculator.MANAGEMENT_ROLES.contains(u.getRole()))
                .toList();
    }
}
