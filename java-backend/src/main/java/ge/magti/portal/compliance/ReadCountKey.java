package ge.magti.portal.compliance;

/**
 * Mirrors the (user_id, target_department) tuple that keys the read-count
 * map built by compliance_utils.py's get_read_counts_by_user_dept /
 * routers/stats.py's _get_read_counts_by_user_dept.
 */
public record ReadCountKey(Long userId, String department) {
}
