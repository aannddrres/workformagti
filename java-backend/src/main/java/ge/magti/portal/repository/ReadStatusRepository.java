package ge.magti.portal.repository;

import ge.magti.portal.domain.ReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReadStatusRepository extends JpaRepository<ReadStatus, Long> {

    Optional<ReadStatus> findByUserIdAndRequiredReadingId(Long userId, Long requiredReadingId);

    /** get_my_readings' per-user status lookup (routers/compliance.py:88-91). */
    List<ReadStatus> findByUserIdAndRequiredReadingIdIn(Long userId, List<Long> requiredReadingIds);

    /**
     * Grouped "read"-status counts keyed by (user_id, RequiredReading.
     * target_department) -- the {@code read_map} that _get_read_counts_by_user_dept
     * builds (routers/stats.py:254-283). Theta-join to RequiredReading on the
     * FK column (ReadStatus has no @ManyToOne mapping to it). Scoped to the
     * given user ids; callers short-circuit an empty list to {} without a
     * query, matching Python. Object[] = {userId (Long), targetDepartment
     * (String), count (Long)}.
     */
    @Query("SELECT rs.userId, rr.targetDepartment, COUNT(rs.id) "
            + "FROM ReadStatus rs, RequiredReading rr "
            + "WHERE rs.requiredReadingId = rr.id AND rs.status = 'read' AND rs.userId IN :userIds "
            + "GROUP BY rs.userId, rr.targetDepartment")
    List<Object[]> readCountsByUserAndDepartment(@Param("userIds") List<Long> userIds);

    /**
     * Mirrors get_statistics_breakdown's "status" dimension
     * (routers/stats.py:902,921-925) -- every read status row, unfiltered.
     * Object[] = {status (String), count (Long)}.
     */
    @Query("SELECT rs.status, COUNT(rs.id) FROM ReadStatus rs GROUP BY rs.status ORDER BY COUNT(rs.id) DESC")
    List<Object[]> countGroupedByStatus();
}
