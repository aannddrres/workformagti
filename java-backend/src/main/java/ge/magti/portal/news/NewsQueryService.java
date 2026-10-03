package ge.magti.portal.news;

import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The news list's filter/visibility/sort logic -- same shape as {@link
 * ge.magti.portal.article.ArticleQueryService}, simpler since News has a
 * single {@code target_department} column instead of a junction table.
 *
 * <p>Not ported: the {@code tech_info}/{@code service_center}
 * role branches -- unreachable dead code, same
 * finding as ArticleQueryService's own javadoc explains (no user can hold
 * either role value through any validated path).
 */
@Service
public class NewsQueryService {

    private static final String LIST_JPQL = """
            SELECT new ge.magti.portal.news.NewsListItem(
              n.id,
              n.title,
              n.targetDepartment,
              n.attachmentUrl,
              n.createdAt,
              n.version,
              n.visibleToTechInfo,
              n.visibleToServiceCenter,
              n.expiresAt,
              n.isDraft,
              n.authorId
            )
            FROM News n
            WHERE (n.isDraft = false OR n.authorId = :userId)
              AND (
                    :isAdmin = true
                    OR (
                      n.isDraft = false
                      AND n.targetDepartment IN :depts
                      AND (n.expiresAt IS NULL OR n.expiresAt >= :now)
                    )
                  )
            ORDER BY
              CASE WHEN n.targetDepartment = :userDept THEN 1 ELSE 0 END DESC,
              n.createdAt DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public List<NewsListItem> listVisible(User user, int skip, int limit) {
        List<String> depts = DepartmentMatcher.visibilityTargets(user.getDepartment());

        TypedQuery<NewsListItem> query = entityManager.createQuery(LIST_JPQL, NewsListItem.class);
        query.setParameter("userId", user.getId());
        query.setParameter("isAdmin", user.seesAllContent());
        query.setParameter("depts", depts);
        query.setParameter("now", TbilisiTime.now());
        query.setParameter("userDept", user.getDepartment());
        // The HTTP boundary rejects invalid/unbounded cardinality before this
        // query is reached. Keeping the values explicit here preserves the
        // legacy arbitrary-offset behavior used by the Angular load-more UI.
        query.setFirstResult(skip);
        query.setMaxResults(limit);
        return query.getResultList();
    }
}
