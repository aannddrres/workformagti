package ge.magti.portal.web;

import ge.magti.portal.stats.TeamMemberCompletion;

import java.util.List;

/** Mirrors schemas.TeamStatsResponse (schemas.py:681-684). */
public record TeamStatsResponse(String department, List<TeamMemberCompletion> members) {
}
