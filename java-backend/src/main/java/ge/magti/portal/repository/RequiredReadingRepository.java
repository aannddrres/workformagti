package ge.magti.portal.repository;

import ge.magti.portal.domain.RequiredReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RequiredReadingRepository extends JpaRepository<RequiredReading, Long> {

    /** Mirrors get_article_read_receipts' single-row lookup (routers/articles.py:1042-1045). */
    Optional<RequiredReading> findFirstByItemTypeAndItemId(String itemType, Long itemId);

    /** Mirrors create_article_read_receipt's compliance-bridge lookup (routers/articles.py:1210-1216) -- prefix-aware (caller passes [dept, deptPrefix, "All"]), unlike EligibleOperatorsService's exact-match rule. */
    List<RequiredReading> findByItemTypeAndItemIdAndTargetDepartmentIn(String itemType, Long itemId, List<String> targetDepartments);

    /** get_required_reading_for_item's by-item lookup (routers/compliance.py:331-334). Distinct from findFirstBy... only in name/intent; both return the first row. */
    Optional<RequiredReading> findFirstByItemTypeAndItemIdOrderByIdAsc(String itemType, Long itemId);

    /** Mirrors get_my_readings' visibility filter (routers/compliance.py:49-51) -- caller passes [dept, deptPrefix, "All"]. */
    List<RequiredReading> findByTargetDepartmentIn(List<String> targetDepartments);

    /**
     * Grouped count of every required reading by its target_department --
     * the {@code readings_by_dept} map compute_compliance builds
     * (routers/stats.py:314-321). Object[] = {targetDepartment (String),
     * count (Long)}.
     */
    @Query("SELECT rr.targetDepartment, COUNT(rr.id) FROM RequiredReading rr GROUP BY rr.targetDepartment")
    List<Object[]> countGroupedByTargetDepartment();
}
