package ge.magti.portal.repository;

import ge.magti.portal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Case-sensitive -- mirrors get_current_user's exact-match lookup (security.py:299). */
    Optional<User> findByEmail(String email);

    /** Case-insensitive -- mirrors authenticate_user's func.lower() lookup (security.py:193). */
    Optional<User> findByEmailIgnoreCase(String email);
}
