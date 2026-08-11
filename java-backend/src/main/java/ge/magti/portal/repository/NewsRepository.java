package ge.magti.portal.repository;

import ge.magti.portal.domain.News;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsRepository extends JpaRepository<News, Long> {

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
