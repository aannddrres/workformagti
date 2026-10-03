package ge.magti.portal.compliance;

/**
 * The (user_id, target_department) key of the per-user, per-department
 * read-count map.
 */
public record ReadCountKey(Long userId, String department) {
}
