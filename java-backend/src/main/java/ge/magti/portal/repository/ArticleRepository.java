package ge.magti.portal.repository;

import ge.magti.portal.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    // routers/categories.py:135 -- bulk-reassigns orphaned articles to the
    // fallback category on delete, same single UPDATE statement Python runs.
    // clearAutomatically: a bulk UPDATE bypasses the persistence context, so
    // without this, an Article already loaded in this transaction (e.g. one
    // just fetched by the caller) would keep serving its stale categoryId
    // from the L1 cache on a later findById instead of re-querying.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Article a SET a.categoryId = :fallbackId WHERE a.categoryId = :oldCategoryId")
    void reassignCategory(Long oldCategoryId, Long fallbackId);

    /**
     * Port of get_stale_articles' filter (routers/articles.py:1543-1547).
     * A NULL lastVerifiedAt never matches "< cutoff" in SQL (NULL
     * comparisons are never true), same as Python's SQLAlchemy filter --
     * not something this query needs to special-case separately.
     */
    List<Article> findByStatusAndLastVerifiedAtBeforeOrderByLastVerifiedAtAsc(String status, OffsetDateTime cutoff);

    List<Article> findByStatus(String status);
}
