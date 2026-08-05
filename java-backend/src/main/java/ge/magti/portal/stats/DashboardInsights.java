package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the "Insights Ribbon" dict routers/stats.py's
 * build_department_stats returns (routers/stats.py:654-659) -- rolled up
 * across every matched member, company-wide. Field names match
 * schemas.InsightsRibbon (schemas.py:726-731) exactly, so this record
 * doubles as the wire shape -- no separate web-layer response needed.
 */
public record DashboardInsights(
        @JsonProperty("global_compliance") int globalCompliance,
        @JsonProperty("critical_operators") int criticalOperators,
        @JsonProperty("total_output_volume") int totalOutputVolume,
        @JsonProperty("total_members") int totalMembers) {
}
