package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The department dashboard's "Insights Ribbon" -- rolled up
 * across every matched member, company-wide. This record
 * doubles as the wire shape -- no separate web-layer response needed.
 */
public record DashboardInsights(
        @JsonProperty("global_compliance") int globalCompliance,
        @JsonProperty("critical_operators") int criticalOperators,
        @JsonProperty("total_output_volume") int totalOutputVolume,
        @JsonProperty("total_members") int totalMembers) {
}
