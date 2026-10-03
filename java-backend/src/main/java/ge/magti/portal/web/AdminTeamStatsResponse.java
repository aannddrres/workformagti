package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.TeamMemberCompletion;

import java.util.List;

public record AdminTeamStatsResponse(
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("average_percentage") String averagePercentage,
        List<TeamMemberCompletion> members) {
}
