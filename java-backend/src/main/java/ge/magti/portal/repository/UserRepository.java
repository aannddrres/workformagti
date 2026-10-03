package ge.magti.portal.repository;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Serializes manual-reminder cooldown checks for the same recipient. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    /** Compare-and-swap used only when permission changes do not dirty the User row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lockVersion = u.lockVersion + 1 "
            + "WHERE u.id = :userId AND u.lockVersion = :expected")
    int advanceLockVersion(@Param("userId") Long userId, @Param("expected") long expected);

    /**
     * Revokes every token already issued to this user, without consulting
     * {@code lock_version}.
     *
     * <p>Phase 6 mapped {@code lock_version} as {@code @Version}, which turns
     * optimistic locking on for every {@code save(User)} in the application --
     * SEC-14's revocation paths included. {@code POST /api/auth/logout} saves
     * the detached {@code @AuthenticationPrincipal}, whose version was read by
     * {@code JwtAuthenticationFilter} at the start of the request; anything
     * that touches the same row inside that window turns the logout into a
     * 409 and leaves the token alive for the rest of its hour.
     *
     * <p>Revoking a token is not a write that may lose a race. It is
     * monotonic -- a second increment is harmless, a skipped one is a live
     * session someone believes they closed -- so it deliberately does not
     * take part in the optimistic-lock compare-and-swap above.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
    int revokeIssuedTokens(@Param("userId") Long userId);

    /**
     * PO-24's "last sign-in", written by every successful sign-in. Nothing wrote
     * {@code last_active} before this, so the leaver filter found every account
     * "never signed in". A targeted update like {@link #revokeIssuedTokens}: it
     * must not advance {@code lock_version}, or an administrator saving that
     * person while they sign in would get a 409 over nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lastActive = :signedInAt WHERE u.id = :userId")
    int recordSignIn(@Param("userId") Long userId, @Param("signedInAt") OffsetDateTime signedInAt);

    /** Case-sensitive -- the signed-in user's exact-match lookup. */
    Optional<User> findByEmail(String email);

    /** Case-insensitive -- the login lookup. */
    Optional<User> findByEmailIgnoreCase(String email);

    /** The user list's group filter. */
    List<User> findByManagerId(Long managerId);

    /**
     * The bulk role reassignment's last-admin-protection count
     * -- active system admins not already in the
     * set about to be demoted.
     */
    long countByRoleAndActiveTrueAndIdNotIn(Role role, List<Long> excludedIds);

    /** The knowledge leaderboard's scope="team" branch. */
    List<User> findByActiveTrueAndTeamId(Long teamId);

    /** Bounded exact-department candidate query for complete-result compliance. */
    List<User> findByActiveTrueAndDepartmentOrderByIdAsc(String department, Pageable pageable);

    List<User> findByActiveTrue();

    /** Bounded candidate query for complete-result compliance calculations. */
    List<User> findByActiveTrueAndRoleOrderByIdAsc(Role role, Pageable pageable);

    /** Active directory membership counts for the Phase 8 read-only org tree. */
    @Query("SELECT u.teamId, COUNT(u.id) FROM User u "
            + "WHERE u.active = true AND u.teamId IS NOT NULL GROUP BY u.teamId")
    List<Object[]> countActiveGroupedByTeam();

    /** Same aggregate restricted to the already bounded org-structure team snapshot. */
    @Query("SELECT u.teamId, COUNT(u.id) FROM User u "
            + "WHERE u.active = true AND u.teamId IN :teamIds GROUP BY u.teamId")
    List<Object[]> countActiveGroupedByTeamIds(@Param("teamIds") Collection<Long> teamIds);

    /** The KPI counts' active-user count. */
    long countByActiveTrue();

    /**
     * The statistics breakdown's "department" dimension
     * -- every user, no active/role filter. Object[] = {department (String), count (Long)}.
     */
    @Query("SELECT u.department, COUNT(u.id) FROM User u GROUP BY u.department ORDER BY COUNT(u.id) DESC")
    List<Object[]> countGroupedByDepartment();

    /** The statistics breakdown's "role" dimension. Object[] = {role (Role), count (Long)}. */
    @Query("SELECT u.role, COUNT(u.id) FROM User u GROUP BY u.role ORDER BY COUNT(u.id) DESC")
    List<Object[]> countGroupedByRole();
}
