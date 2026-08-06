package ge.magti.portal.repository;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Case-sensitive -- mirrors get_current_user's exact-match lookup (security.py:299). */
    Optional<User> findByEmail(String email);

    /** Case-insensitive -- mirrors authenticate_user's func.lower() lookup (security.py:193). */
    Optional<User> findByEmailIgnoreCase(String email);

    /** Mirrors list_users' Block-5 group filter (routers/users.py:274-275). */
    List<User> findByManagerId(Long managerId);

    /** Mirrors get_group_leaders (routers/users.py:246-250). */
    List<User> findByRoleOrderByName(Role role);

    /**
     * Mirrors bulk_reassign_roles' last-admin-protection count
     * (routers/users.py:140-148) -- active system admins not already in the
     * set about to be demoted.
     */
    long countByRoleAndActiveTrueAndIdNotIn(Role role, List<Long> excludedIds);

    /** Mirrors get_knowledge_leaderboard's scope="team" branch (routers/articles.py:907-908). */
    List<User> findByActiveTrueAndTeamId(Long teamId);

    /** Mirrors get_knowledge_leaderboard's scope="department" branch (routers/articles.py:910), exact match only -- no prefix expansion. */
    List<User> findByActiveTrueAndDepartment(String department);

    List<User> findByActiveTrue();

    /** Mirrors _get_eligible_operators' non-"All" branch (routers/articles.py:992-993), exact match only -- no prefix expansion. */
    List<User> findByActiveTrueAndDepartmentIn(List<String> departments);

    /** Mirrors get_kpi_counts' active-user count subquery (routers/stats.py:952-953). */
    long countByActiveTrue();

    /**
     * Mirrors get_statistics_breakdown's "department" dimension
     * (routers/stats.py:900,921-925) -- every user, no active/role filter
     * (Python applies none here). Object[] = {department (String), count (Long)}.
     */
    @Query("SELECT u.department, COUNT(u.id) FROM User u GROUP BY u.department ORDER BY COUNT(u.id) DESC")
    List<Object[]> countGroupedByDepartment();

    /** Mirrors get_statistics_breakdown's "role" dimension (routers/stats.py:901,921-925). Object[] = {role (Role), count (Long)}. */
    @Query("SELECT u.role, COUNT(u.id) FROM User u GROUP BY u.role ORDER BY COUNT(u.id) DESC")
    List<Object[]> countGroupedByRole();
}
