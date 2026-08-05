package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors the dict routers/stats.py's build_department_stats returns
 * (routers/stats.py:661-665), matching schemas.DepartmentStatsResponse
 * field-for-field -- doubles as the wire shape.
 */
public record DepartmentDashboard(
        DashboardInsights insights,
        List<DepartmentStats> departments,
        @JsonProperty("generated_at") OffsetDateTime generatedAt) {
}
