package ge.magti.portal.user;

import ge.magti.portal.domain.User;
import ge.magti.portal.domain.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bounded database query for the administrative user directory.
 *
 * <p>The response contract remains a plain list, but offset and limit are
 * applied by Oracle rather than after {@code findAll()} has hydrated the
 * complete directory. ID ordering makes successive slices deterministic.
 */
@Service
public class UserDirectoryQueryService {

    public static final int MAX_DIRECTORY_USERS = 1_000;
    public static final int MAX_ACTIVE_USERS = 1_000;

    private final EntityManager entityManager;

    public UserDirectoryQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<User> list(Long managerId, int skip, int limit) {
        String jpql = managerId == null
                ? "SELECT u FROM User u ORDER BY u.id"
                : "SELECT u FROM User u WHERE u.managerId = :managerId ORDER BY u.id";
        TypedQuery<User> query = entityManager.createQuery(jpql, User.class);
        if (managerId != null) {
            query.setParameter("managerId", managerId);
        }
        query.setFirstResult(skip);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /** Current compliance eligibility is exactly active operators. */
    @Transactional(readOnly = true)
    public List<User> listActiveOperators(int skip, int limit) {
        return entityManager.createQuery(
                        "SELECT u FROM User u WHERE u.active = true AND u.role = :role ORDER BY u.id",
                        User.class)
                .setParameter("role", Role.OPERATOR)
                .setFirstResult(skip)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Active directory snapshot for complete-result administrative jobs. */
    @Transactional(readOnly = true)
    public List<User> listActiveUsers(int skip, int limit) {
        return entityManager.createQuery(
                        "SELECT u FROM User u WHERE u.active = true ORDER BY u.id",
                        User.class)
                .setFirstResult(skip)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Bounded active snapshot for exact-match department target lists. */
    @Transactional(readOnly = true)
    public List<User> listActiveUsersInDepartmentsWithinLimit(List<String> departments) {
        List<User> users = entityManager.createQuery(
                        "SELECT u FROM User u WHERE u.active = true "
                                + "AND u.department IN :departments ORDER BY u.id",
                        User.class)
                .setParameter("departments", departments)
                .setMaxResults(MAX_ACTIVE_USERS + 1)
                .getResultList();
        return requireWithinActiveUserLimit(users);
    }

    /** Complete directory snapshot, including inactive users, for access-diff evidence. */
    @Transactional(readOnly = true)
    public List<User> listUsersWithinLimit() {
        return requireWithinDirectoryLimit(list(null, 0, MAX_DIRECTORY_USERS + 1));
    }

    /** Complete role-specific directory list for legacy plain-array selectors. */
    @Transactional(readOnly = true)
    public List<User> listUsersByRoleWithinLimit(Role role) {
        List<User> users = entityManager.createQuery(
                        "SELECT u FROM User u WHERE u.role = :role ORDER BY u.name, u.id",
                        User.class)
                .setParameter("role", role)
                .setMaxResults(MAX_DIRECTORY_USERS + 1)
                .getResultList();
        return requireWithinDirectoryLimit(users);
    }

    /** Complete-result snapshot: fail rather than silently truncate. */
    @Transactional(readOnly = true)
    public List<User> listActiveUsersWithinLimit() {
        return requireWithinActiveUserLimit(listActiveUsers(0, MAX_ACTIVE_USERS + 1));
    }

    public static List<User> requireWithinActiveUserLimit(List<User> users) {
        if (users.size() > MAX_ACTIVE_USERS) {
            throw new ActiveUserCardinalityExceededException();
        }
        return users;
    }

    public static List<User> requireWithinDirectoryLimit(List<User> users) {
        if (users.size() > MAX_DIRECTORY_USERS) {
            throw new AllUserCardinalityExceededException();
        }
        return users;
    }

    public static class UserDirectoryCardinalityExceededException extends RuntimeException {
        protected UserDirectoryCardinalityExceededException(String message) {
            super(message);
        }
    }

    public static class ActiveUserCardinalityExceededException
            extends UserDirectoryCardinalityExceededException {
        public ActiveUserCardinalityExceededException() {
            super("Active user directory exceeds the bounded complete-result snapshot");
        }
    }

    public static class AllUserCardinalityExceededException
            extends UserDirectoryCardinalityExceededException {
        public AllUserCardinalityExceededException() {
            super("User directory exceeds the bounded complete-result snapshot");
        }
    }
}
