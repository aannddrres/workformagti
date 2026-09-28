package ge.magti.portal.repository;

import ge.magti.portal.domain.News;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsRepository extends JpaRepository<News, Long> {

    /**
     * The bell's recent news: {@link ge.magti.portal.news.NewsVisibility}'s
     * rule in JPQL, newest first. A content administrator sees everything but
     * another author's private draft; anyone else, what is not a draft, not
     * expired (archived) and addressed to them. The two derived queries this
     * replaces filtered on date and department alone, so the bell listed
     * drafts -- a colleague's private one included -- and expired news by
     * title.
     *
     * @param departments {@code DepartmentMatcher.visibilityTargets} of the
     *                    caller's department -- prefix-aware, like
     *                    GET /api/news (bug #315)
     */
    @Query("SELECT n FROM News n WHERE n.createdAt >= :cutoff "
            + "AND (n.isDraft = false OR n.authorId = :viewerId) "
            + "AND (:contentAdmin = true OR (n.isDraft = false AND n.targetDepartment IN :departments "
            + "AND (n.expiresAt IS NULL OR n.expiresAt > :now))) "
            + "ORDER BY n.createdAt DESC")
    List<News> findRecentVisibleTo(
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("now") OffsetDateTime now,
            @Param("viewerId") Long viewerId,
            @Param("contentAdmin") boolean contentAdmin,
            @Param("departments") List<String> departments,
            Pageable pageable);

    /**
     * NewsVisibility.isPrivateDraftOfAnother in SQL, trash included: the
     * entity's trashed_at restriction hides a trashed row from findById.
     */
    @Query(value = "SELECT COUNT(*) FROM news WHERE id = :id AND is_draft = 1 "
            + "AND (author_id IS NULL OR author_id <> :viewerId)", nativeQuery = true)
    long countPrivateDraftOfAnotherIncludingTrash(@Param("id") Long id, @Param("viewerId") Long viewerId);
}
