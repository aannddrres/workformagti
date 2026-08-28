package ge.magti.portal.repository;

import ge.magti.portal.domain.Department;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByStableKey(String stableKey);

    /** Matches the Georgian display string the free-text mapper works from. */
    Optional<Department> findByName(String name);

    List<Department> findByActiveTrueOrderBySortOrder(Pageable pageable);
}
