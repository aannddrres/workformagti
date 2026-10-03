package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The DB-free half of the admin and manager team stats -- both group by
 * {@code User.team_id}, a foreign key genuinely distinct from the
 * department-string hierarchy {@link DepartmentStatsBuilder}/
 * {@link OperatorStatsBuilder} use. The department dashboard does not use
 * team_id because the seed never populated it -- team_id grouping
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
     * The admin and manager team stats' member rows (both build the same
     * shape; the admin one additionally reduces it to an average). Sorted
     * by percentage descending, by the already-known int percentage rather
     * than by re-parsing the "NN%" label.
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
     * The average TRUNCATES toward zero (0 with no users) -- a different
     * operation from the half-to-even rounding
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
