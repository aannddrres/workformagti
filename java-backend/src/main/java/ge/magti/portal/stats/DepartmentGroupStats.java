package ge.magti.portal.stats;

import java.util.List;

/**
 * Mirrors one group entry routers/stats.py's build_department_stats
 * assembles (routers/stats.py:622-637) -- members sorted by descending
 * percentage.
 */
public record DepartmentGroupStats(
        String name,
        String fullDepartment,
        int memberCount,
        int compliance,
        int outputVolume,
        int criticalCount,
        List<DepartmentMember> members) {
}
