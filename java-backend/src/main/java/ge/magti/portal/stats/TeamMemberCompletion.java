package ge.magti.portal.stats;

/**
 * Mirrors the per-member dict routers/stats.py's get_admin_team_stats/
 * get_team_stats build (routers/stats.py:427-433, 515-521). Note
 * {@link #percentageLabel} is a pre-formatted "NN%" string, not a plain
 * int -- that's what these two endpoints actually return today (unlike
 * every other stats endpoint in this codebase, which returns a plain
 * int percentage), carried over as-is rather than "fixed" to match.
 */
public record TeamMemberCompletion(Long userId, String userName, int readCount, int requiredCount, String percentageLabel) {
}
