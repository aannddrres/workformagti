package ge.magti.portal.repository;

import ge.magti.portal.domain.ReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReadStatusRepository extends JpaRepository<ReadStatus, Long> {

    Optional<ReadStatus> findByUserIdAndRequiredReadingId(Long userId, Long requiredReadingId);

    /**
     * BL-02: the half of the deletion order that must run first --
     * {@code read_statuses.required_reading_id} FKs to {@code
     * required_readings}, so these rows have to be gone before the readings
     * themselves are deleted. {@code @Modifying} bulk JPQL rather than a
     * derived delete for the same reason as {@link
     * SearchTrigramRepository#deleteByEntityTypeAndEntityId}: the delete must
     * hit Oracle before the subsequent required_readings delete runs in the
     * same transaction, not queue for flush time.
     */
    @Modifying
    @Query("DELETE FROM ReadStatus rs WHERE rs.requiredReadingId IN :requiredReadingIds")
    void deleteByRequiredReadingIdIn(@Param("requiredReadingIds") List<Long> requiredReadingIds);

    /**
     * The readings exports' shared ReadStatus scan,
     * now uniformly scoped to eligible user ids for all three formats (see
     * {@link ge.magti.portal.export.ExportQueryService}).
     */
    List<ReadStatus> findByUserIdIn(List<Long> userIds, Pageable pageable);

    /** The same scan, for readings whose deadline is in [from, before) -- the export's period. */
    @Query("SELECT rs FROM ReadStatus rs WHERE rs.userId IN :userIds AND rs.requiredReadingId IN "
            + "(SELECT r.id FROM RequiredReading r WHERE r.dueDate >= :dueFrom AND r.dueDate < :dueBefore)")
    List<ReadStatus> findByUserIdInAndDueDateWithin(
            @Param("userIds") List<Long> userIds,
            @Param("dueFrom") java.time.OffsetDateTime dueFrom,
            @Param("dueBefore") java.time.OffsetDateTime dueBefore,
            Pageable pageable);

    /** The reading list's per-user status lookup. */
    List<ReadStatus> findByUserIdAndRequiredReadingIdIn(Long userId, List<Long> requiredReadingIds);

    /**
     * PO-40's editor warning: who has confirmed any of one item's readings.
     * Callers pass a sentinel page and fail loudly past it.
     */
    List<ReadStatus> findByRequiredReadingIdInAndStatus(
            List<Long> requiredReadingIds, String status, Pageable pageable);

    /**
     * The statistics breakdown's "status" dimension
     * -- every read status row, unfiltered.
     * Object[] = {status (String), count (Long)}.
     */
    @Query("SELECT rs.status, COUNT(rs.id) FROM ReadStatus rs GROUP BY rs.status ORDER BY COUNT(rs.id) DESC")
    List<Object[]> countGroupedByStatus();
}
