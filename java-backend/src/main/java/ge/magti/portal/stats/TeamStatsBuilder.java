package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ports the DB-free half of routers/stats.py's get_admin_team_stats
 * (:394-443) and get_team_stats (:446-524) -- both group by
 * {@code User.team_id}, a foreign key genuinely distinct from the
 * department-string hierarchy {@link DepartmentStatsBuilder}/
 * {@link OperatorStatsBuilder} use. routers/stats.py:530-531 notes *why*
 * they diverge: "The Team/team_id FK is NOT used [in the department
 * dashboard] because the seed never populates it" -- team_id grouping
 * exists in the API but has no seeded data to exercise it today. Ported
 * anyway rather than skipped, since the endpoints are real and the
 * decision to seed team_id or not is a data question, not a code one.
 *
 * <p>The RBAC-scoped filtering (manager pinned to their own department,
 * admin optionally restricting by department/team_id, name substring
 * search) is all DB-query construction and is not ported -- repository
 * work, same as everywhere else in this domain.
 */
public final class TeamStatsBuilder {

    private TeamStatsBuilder() {
    }

    /**
     * Mirrors get_admin_team_stats/get_team_stats:421-437,510-521 combined
     * (both build the same shape; get_admin_team_stats additionally reduces
     * it to an average). Sorted by percentage descending. Deliberately
     * sorts by the already-known int percentage rather than Python's own
     * quirk of formatting to "NN%" and then re-parsing that string back to
     * an int just to sort by it (routers/stats.py:437,523) -- same final
     * order, without the pointless round trip.
     */
    public static List<TeamMemberCompletion> buildTeamMemberCompletions(List<ComplianceRecord> records) {
        List<ComplianceRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingInt((ComplianceRecord r) -> r.progress().percentage()).reversed());

        List<TeamMemberCompletion> members = new ArrayList<>();
        for (ComplianceRecord record : sorted) {
            ReadingProgress progress = record.progress();
            members.add(new TeamMemberCompletion(
                    record.user().getId(),
                    record.user().getName(),
                    progress.readCount(),
                    progress.requiredCount(),
                    progress.percentage() + "%"));
        }
        return members;
    }

    /**
     * Mirrors get_admin_team_stats:436: {@code int(total_percentage /
     * len(users)) if users else 0}. Python's {@code int()} here TRUNCATES
     * toward zero -- a different operation from the plain {@code round()}
     * used everywhere else in this domain (see
     * {@link ge.magti.portal.compliance.ComplianceCalculator}'s Javadoc).
     * Plain Java integer division already truncates toward zero for these
     * always-non-negative values, so no {@code Math.rint}/{@code Math.floor}
     * call is needed or correct here -- using either would silently change
     * the result on a non-exact average.
     */
    public static int averagePercentage(List<ComplianceRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        int totalPercentage = 0;
        for (ComplianceRecord record : records) {
            totalPercentage += record.progress().percentage();
        }
        return totalPercentage / records.size();
    }
}
