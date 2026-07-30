package ge.magti.portal.stats;

/** Mirrors the per-user dict routers/stats.py's get_group_users builds (routers/stats.py:802-806). */
public record GroupMemberCompletion(Long userId, String firstName, String lastName, int completionPercentage) {
}
