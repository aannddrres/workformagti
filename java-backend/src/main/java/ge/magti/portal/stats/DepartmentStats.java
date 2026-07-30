package ge.magti.portal.stats;

import java.util.List;

/**
 * Mirrors one top-level department entry routers/stats.py's
 * build_department_stats assembles (routers/stats.py:619-651). Always one
 * of these per {@link DepartmentBuckets#WHITELIST} entry, even when
 * {@code empty} -- the dashboard renders all three whitelisted
 * departments unconditionally.
 */
public record DepartmentStats(
        String name,
        int memberCount,
        int groupCount,
        int compliance,
        int outputVolume,
        int criticalCount,
        boolean empty,
        List<DepartmentGroupStats> groups) {
}
