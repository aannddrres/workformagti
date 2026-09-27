package ge.magti.portal.repository;

import ge.magti.portal.domain.News;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsRepository extends JpaRepository<News, Long> {

    /** Private history keeps its owner boundary even when the parent is in trash. */
    @Query(value = "SELECT COUNT(*) FROM news WHERE id = :id AND is_draft = 1 "
            + "AND (author_id IS NULL OR author_id <> :userId)", nativeQuery = true)
    long countInaccessiblePrivateDraftIncludingTrash(Long id, Long userId);

    @Query(value = "SELECT id FROM news WHERE id IN :ids AND is_draft = 1 "
            + "AND (author_id IS NULL OR author_id <> :userId)", nativeQuery = true)
    List<Long> findInaccessiblePrivateDraftIdsIncludingTrash(java.util.Collection<Long> ids, Long userId);

    @Query("SELECT n FROM News n WHERE n.createdAt >= :cutoff "
            + "AND (n.isDraft = false OR n.authorId = :userId) "
            + "AND (:isAdmin = true OR (n.isDraft = false AND n.targetDepartment IN :depts "
            + "AND (n.expiresAt IS NULL OR n.expiresAt > :now))) ORDER BY n.createdAt DESC")
    List<News> findVisibleRecent(OffsetDateTime cutoff, OffsetDateTime now, Long userId,
            boolean isAdmin, List<String> depts, Pageable pageable);

    /**
     * Notifications-summary's content-admin branch (routers/platform.py:176,
     * 184) -- no department filter.
     */
    List<News> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(OffsetDateTime cutoff, Pageable pageable);

    /**
     * Notifications-summary's non-admin branch (routers/platform.py:177-180,
     * 184): exact department match only (department string or "All") --
     * deliberately NOT DepartmentMatcher's prefix-aware matching, matching
     * Python's plain {@code .in_([current_user.department, "All"])} exactly.
     */
    List<News> findByCreatedAtGreaterThanEqualAndTargetDepartmentInOrderByCreatedAtDesc(
            OffsetDateTime cutoff, List<String> departments, Pageable pageable);
}
