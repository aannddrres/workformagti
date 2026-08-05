package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.TeamMemberCompletion;

import java.util.List;

/** Mirrors get_admin_team_stats' ad-hoc dict (routers/stats.py:439-443, response_model=dict -- no formal schema in Python). */
public record AdminTeamStatsResponse(
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("average_percentage") String averagePercentage,
        List<TeamMemberCompletion> members) {
}
