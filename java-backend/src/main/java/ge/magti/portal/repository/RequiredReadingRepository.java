package ge.magti.portal.repository;

import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.LockModeType;

public interface RequiredReadingRepository extends JpaRepository<RequiredReading, Long> {

    /** Serializes scheduled delivery for one reading across app replicas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM RequiredReading rr WHERE rr.id = :readingId")
    Optional<RequiredReading> findByIdForUpdate(@Param("readingId") Long readingId);

    /** Mirrors get_article_read_receipts' single-row lookup (routers/articles.py:1042-1045). */
    Optional<RequiredReading> findFirstByItemTypeAndItemId(String itemType, Long itemId);

    /**
     * BL-02: unlike {@link #findFirstByItemTypeAndItemId}, this returns every
     * matching row -- V6 declares no uniqueness on (item_type, item_id), so
     * more than one required-reading row (different due dates or target
     * departments) can reference the same item. Deleting an item must remove
     * all of them, not just the first.
     */
    List<RequiredReading> findByItemTypeAndItemId(String itemType, Long itemId);

    /**
     * Mirrors create_article_read_receipt's compliance-bridge lookup
     * (routers/articles.py:1210-1216) -- prefix-aware (caller passes [dept,
     * deptPrefix, "All"]), unlike EligibleOperatorsService's exact-match
     * rule. V6 has no item/target uniqueness constraint, so the caller uses
     * a 1,001-row sentinel page and fails loudly rather than hydrating an
     * unbounded relation or silently skipping matching readings.
     */
    List<RequiredReading> findByItemTypeAndItemIdAndTargetDepartmentIn(
            String itemType, Long itemId, List<String> targetDepartments, Pageable pageable);

    /** get_required_reading_for_item's by-item lookup (routers/compliance.py:331-334). Distinct from findFirstBy... only in name/intent; both return the first row. */
    Optional<RequiredReading> findFirstByItemTypeAndItemIdOrderByIdAsc(String itemType, Long itemId);

    /**
     * PO-40: readings whose assignment reminders have not gone out yet -- made
     * mandatory before their article is published. The reminder sweep
     * delivers each once it comes into force.
     */
    @Query("SELECT rr.id FROM RequiredReading rr WHERE rr.assignmentDeliveredAt IS NULL ORDER BY rr.id")
    List<Long> findIdsAwaitingAssignmentDelivery(Pageable pageable);

    /** Mirrors get_my_readings' visibility filter (routers/compliance.py:49-51) -- caller passes [dept, deptPrefix, "All"]. */
    List<RequiredReading> findByTargetDepartmentIn(List<String> targetDepartments, Pageable pageable);

    /**
     * Mirrors get_compliance_statistics' top-5-most-read-articles query
     * (routers/stats.py:175-197): required readings of type "article",
     * counting "read" statuses from active, non-management users, for
     * articles that actually have at least one target-department row
     * (Article.target_department_rows.any()) -- articles with none would
     * fail ArticleResponse validation downstream, so they're excluded here
     * rather than crashing the endpoint. Theta-join across three entities.
     * Object[] = {articleId (Long), readCount (Long)}, caller applies the limit
     * via {@code pageable}.
     */
    @Query("SELECT rr.itemId, COUNT(rs.id) FROM RequiredReading rr, ge.magti.portal.domain.ReadStatus rs, ge.magti.portal.domain.User u "
            + "WHERE rr.itemType = 'article' AND rs.requiredReadingId = rr.id AND rs.userId = u.id "
            + "AND rs.status = 'read' AND u.active = true AND u.role NOT IN :managementRoles "
            + "AND EXISTS (SELECT 1 FROM ge.magti.portal.domain.ArticleTargetDepartment atd WHERE atd.articleId = rr.itemId) "
            + "GROUP BY rr.itemId ORDER BY COUNT(rs.id) DESC")
    List<Object[]> topReadArticleIds(@Param("managementRoles") Set<Role> managementRoles, Pageable pageable);
}
