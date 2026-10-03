package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The department dashboard -- doubles as the wire shape.
 */
public record DepartmentDashboard(
        DashboardInsights insights,
        List<DepartmentStats> departments,
        @JsonProperty("generated_at") OffsetDateTime generatedAt) {
}
