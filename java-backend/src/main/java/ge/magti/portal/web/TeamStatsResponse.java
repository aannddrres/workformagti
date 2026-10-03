package ge.magti.portal.web;

import ge.magti.portal.stats.TeamMemberCompletion;

import java.util.List;

public record TeamStatsResponse(String department, List<TeamMemberCompletion> members) {
}
