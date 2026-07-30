package ge.magti.portal.stats;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors the dict routers/stats.py's build_department_stats returns
 * (routers/stats.py:661-665), matching schemas.DepartmentStatsResponse.
 */
public record DepartmentDashboard(
        DashboardInsights insights,
        List<DepartmentStats> departments,
        OffsetDateTime generatedAt) {
}
