package ge.magti.portal.repository;

import ge.magti.portal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Case-sensitive -- mirrors get_current_user's exact-match lookup (security.py:299). */
    Optional<User> findByEmail(String email);

    /** Case-insensitive -- mirrors authenticate_user's func.lower() lookup (security.py:193). */
    Optional<User> findByEmailIgnoreCase(String email);

    /** Mirrors get_knowledge_leaderboard's scope="team" branch (routers/articles.py:907-908). */
    List<User> findByActiveTrueAndTeamId(Long teamId);

    /** Mirrors get_knowledge_leaderboard's scope="department" branch (routers/articles.py:910), exact match only -- no prefix expansion. */
    List<User> findByActiveTrueAndDepartment(String department);
}
