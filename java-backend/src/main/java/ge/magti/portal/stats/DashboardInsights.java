package ge.magti.portal.stats;

/**
 * Mirrors the "Insights Ribbon" dict routers/stats.py's
 * build_department_stats returns (routers/stats.py:654-659) -- rolled up
 * across every matched member, company-wide.
 */
public record DashboardInsights(
        int globalCompliance,
        int criticalOperators,
        int totalOutputVolume,
        int totalMembers) {
}
