package ge.magti.portal.repository;

import ge.magti.portal.domain.RequiredReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequiredReadingRepository extends JpaRepository<RequiredReading, Long> {

    /** Mirrors get_article_read_receipts' single-row lookup (routers/articles.py:1042-1045). */
    Optional<RequiredReading> findFirstByItemTypeAndItemId(String itemType, Long itemId);

    /** Mirrors create_article_read_receipt's compliance-bridge lookup (routers/articles.py:1210-1216) -- prefix-aware (caller passes [dept, deptPrefix, "All"]), unlike EligibleOperatorsService's exact-match rule. */
    List<RequiredReading> findByItemTypeAndItemIdAndTargetDepartmentIn(String itemType, Long itemId, List<String> targetDepartments);
}
