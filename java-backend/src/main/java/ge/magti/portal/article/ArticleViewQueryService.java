package ge.magti.portal.article;

import ge.magti.portal.domain.ArticleViewLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Database-side aggregates and exact offset paging for article view evidence. */
@Service
public class ArticleViewQueryService {

    private final EntityManager entityManager;

    public ArticleViewQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public ViewPage query(long articleId, Integer version, int offset, int limit) {
        String predicate = "v.articleIdSnapshot = :articleId"
                + (version == null ? "" : " AND v.articleVersion = :version");

        TypedQuery<Long> totalQuery = entityManager.createQuery(
                "SELECT COUNT(v) FROM ArticleViewLog v WHERE " + predicate, Long.class);
        TypedQuery<Long> uniqueQuery = entityManager.createQuery(
                "SELECT COUNT(DISTINCT v.operatorId) FROM ArticleViewLog v WHERE " + predicate,
                Long.class);
        TypedQuery<ArticleViewLog> rowsQuery = entityManager.createQuery(
                "SELECT v FROM ArticleViewLog v WHERE " + predicate + " ORDER BY v.viewedAt DESC",
                ArticleViewLog.class);

        bind(totalQuery, articleId, version);
        bind(uniqueQuery, articleId, version);
        bind(rowsQuery, articleId, version);
        rowsQuery.setFirstResult(offset);
        rowsQuery.setMaxResults(limit);

        return new ViewPage(
                totalQuery.getSingleResult(),
                uniqueQuery.getSingleResult(),
                rowsQuery.getResultList());
    }

    private static void bind(TypedQuery<?> query, long articleId, Integer version) {
        query.setParameter("articleId", articleId);
        if (version != null) {
            query.setParameter("version", version);
        }
    }

    public record ViewPage(long totalViews, long uniqueViewers, List<ArticleViewLog> rows) {
        public ViewPage {
            rows = List.copyOf(rows);
        }
    }
}
