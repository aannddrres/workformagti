package ge.magti.portal.stats;

/**
 * Mirrors the per-member dict routers/stats.py's build_department_stats
 * builds (routers/stats.py:607-615).
 */
public record DepartmentMember(
        Long userId,
        String userName,
        String position,
        int readCount,
        int requiredCount,
        int percentage,
        boolean critical) {
}
