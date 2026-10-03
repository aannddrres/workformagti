package ge.magti.portal.repository;

import ge.magti.portal.domain.Article;
import ge.magti.portal.article.ArticleReferenceItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    // Bulk-reassigns orphaned articles to the
    // fallback category on delete, in a single UPDATE statement.
    // clearAutomatically: a bulk UPDATE bypasses the persistence context, so
    // without this, an Article already loaded in this transaction (e.g. one
    // just fetched by the caller) would keep serving its stale categoryId
    // from the L1 cache on a later findById instead of re-querying.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Article a SET a.categoryId = :fallbackId WHERE a.categoryId = :oldCategoryId")
    void reassignCategory(Long oldCategoryId, Long fallbackId);

    /** Includes trashed rows: restoring one must never point at a category that was deleted meanwhile. */
    @Query(value = "SELECT COUNT(*) FROM articles WHERE category_id = :categoryId", nativeQuery = true)
    long countAllByCategoryIdIncludingTrash(Long categoryId);

    /**
     * ArticleVisibility.isPrivateDraftOfAnother in SQL, trash included. The
     * entity's trashed_at restriction hides a trashed row from findById, and
     * the history endpoints read "not found" as an id with no article -- and
     * answered with its history, a colleague's private draft included.
     */
    @Query(value = "SELECT COUNT(*) FROM articles WHERE id = :id AND is_draft = 1 "
            + "AND (author_id IS NULL OR author_id <> :viewerId)", nativeQuery = true)
    long countPrivateDraftOfAnotherIncludingTrash(@Param("id") Long id, @Param("viewerId") Long viewerId);

    /**
     * The stale-articles filter.
     * A NULL lastVerifiedAt never matches "< cutoff" in SQL (NULL
     * comparisons are never true) --
     * not something this query needs to special-case separately.
     *
     * <p>Both reference queries carry ArticleQueryService's draft clause
     * ({@code a.isDraft = false OR a.authorId = :viewerId}): a legacy row can
     * say published while is_draft is still set, and its title belongs to
     * its author alone (PO-34).
     */
    @Query("SELECT new ge.magti.portal.article.ArticleReferenceItem("
            + "a.id, a.title, a.categoryId, a.tags, a.createdAt, a.lastVerifiedAt) "
            + "FROM Article a WHERE a.status = :status AND a.lastVerifiedAt < :cutoff "
            + "AND (a.isDraft = false OR a.authorId = :viewerId) "
            + "ORDER BY a.lastVerifiedAt ASC")
    List<ArticleReferenceItem> findStaleReferences(
            @Param("status") String status,
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("viewerId") Long viewerId,
            Pageable pageable);

    @Query("SELECT new ge.magti.portal.article.ArticleReferenceItem("
            + "a.id, a.title, a.categoryId, a.tags, a.createdAt, a.lastVerifiedAt) "
            + "FROM Article a WHERE a.status = :status "
            + "AND (a.isDraft = false OR a.authorId = :viewerId)")
    List<ArticleReferenceItem> findReferencesByStatus(
            @Param("status") String status,
            @Param("viewerId") Long viewerId,
            Pageable pageable);

    /** Bounded category-only search fallback; callers supply the hard ceiling. */
    List<Article> findByCategoryId(Long categoryId, Pageable pageable);
}
