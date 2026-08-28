package ge.magti.portal.article;

import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentGroup;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Faithful port of get_articles' filter/visibility/sort logic
 * (routers/articles.py:97-176). A plain JPQL query with conditional bind
 * parameters (the {@code :param IS NULL OR ...} idiom) rather than Spring
 * Data's {@code Pageable}, since {@code skip}/{@code limit} here are
 * arbitrary offsets -- not page-number-aligned, which is all {@code
 * Pageable} can express.
 *
 * <p><b>Not ported: the {@code tech_info}/{@code service_center} role
 * branches</b> ("Block 5" in the Python source, routers/articles.py:164-167).
 * {@code security.py}'s {@code VALID_ROLES}/{@code _validate_role} only ever
 * allow a user's role to be one of operator/manager/content_admin/admin --
 * "tech_info" and "service_center" can never actually be assigned to any
 * user through any validated code path today, so those two branches are
 * unreachable dead code in the source itself, not a gap this port is
 * introducing. {@link ge.magti.portal.domain.Role} correctly has no such
 * values, so there is nothing to compare against. The {@code
 * visible_to_tech_info}/{@code visible_to_service_center} *columns* still
 * exist and are still read/written elsewhere (kept, unchanged) -- only the
 * never-reachable role-gated filter is not reproduced.
 *
 * <p>Python's {@code defer(content)} list-view optimization is represented by
 * a constructor projection. V45 maintains the read-time scalar in Oracle, so
 * the list retains its exact response shape without selecting the content CLOB.
 */
@Service
public class ArticleQueryService {

    private static final String LIST_JPQL = """
            SELECT new ge.magti.portal.article.ArticleListItem(
              a.id,
              a.title,
              a.categoryId,
              a.tags,
              a.status,
              a.publishedAt,
              a.createdAt,
              a.readTime,
              a.audienceProfile,
              a.visibleToTechInfo,
              a.visibleToServiceCenter,
              a.isDraft
            )
            FROM Article a
            WHERE (a.isDraft = false OR a.authorId = :userId)
              AND (:q IS NULL OR a.title LIKE :qPattern)
              AND (:categoryId IS NULL OR a.categoryId = :categoryId)
              AND (:status IS NULL OR a.status = :status)
              AND (
                    :isAdmin = true
                    OR (
                      a.isDraft = false
                      AND EXISTS (
                        SELECT 1 FROM ArticleTargetDepartment t
                        WHERE t.articleId = a.id AND t.department IN :depts
                      )
                      AND (
                        a.status = 'published'
                        OR (a.status = 'scheduled' AND a.publishedAt <= :now)
                      )
                    )
                  )
            ORDER BY
              CASE WHEN EXISTS (
                SELECT 1 FROM ArticleTargetDepartment t2
                WHERE t2.articleId = a.id AND t2.department = :userDept
              ) THEN 1 ELSE 0 END DESC,
              a.createdAt DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public List<ArticleListItem> listVisible(ArticleListFilter filter, User user, int skip, int limit) {
        DepartmentGroup group = DepartmentMatcher.splitGroup(user.getDepartment());
        List<String> depts = List.of(user.getDepartment(), group.prefix(), "All");

        TypedQuery<ArticleListItem> query = entityManager.createQuery(LIST_JPQL, ArticleListItem.class);
        query.setParameter("userId", user.getId());
        query.setParameter("q", filter.q());
        query.setParameter("qPattern", filter.q() == null ? null : "%" + filter.q() + "%");
        query.setParameter("categoryId", filter.categoryId());
        query.setParameter("status", filter.status());
        query.setParameter("isAdmin", user.getRole().isContentAdmin());
        query.setParameter("depts", depts);
        query.setParameter("now", TbilisiTime.now());
        query.setParameter("userDept", user.getDepartment());
        // The HTTP boundary rejects invalid/unbounded cardinality before this
        // query is reached. Keeping the values explicit here preserves the
        // legacy arbitrary-offset behavior used by current Angular views.
        query.setFirstResult(skip);
        query.setMaxResults(limit);
        return query.getResultList();
    }
}
