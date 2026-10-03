package ge.magti.portal.stats;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentGroup;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The DB-free half of the critical-operators and group-users lists -- both
 * are three DB queries
 * followed by pure aggregation over already-loaded users/progress; only
 * the aggregation is here.
 */
public final class OperatorStatsBuilder {

    private OperatorStatsBuilder() {
    }

    /**
     * Operators who need a leader's attention: below
     * {@link ComplianceCalculator#CRITICAL_THRESHOLD}, or with anything past
     * its deadline, sorted by most overdue first.
     *
     * <p>{@code overdue_count} is what is owed AND past its deadline. It used
     * to be required minus read, so a reading due tomorrow showed as overdue
     * today on the admin overview ("ვადაგადაცილებული ოპერატორი"), the users
     * table and the leader's list -- and someone at 90% with one item a week
     * late did not appear at all (simulation, 2026-10-01).
     */
    public static List<CriticalOperator> buildCriticalOperators(List<ComplianceRecord> records) {
        List<CriticalOperator> operators = new ArrayList<>();
        for (ComplianceRecord record : records) {
            ReadingProgress progress = record.progress();
            if (progress.critical()) {
                String[] parts = DisplayName.splitFirstLast(record.user().getName());
                operators.add(new CriticalOperator(
                        record.user().getId(),
                        DisplayName.firstName(parts),
                        DisplayName.lastName(parts),
                        record.user().getDepartment(),
                        progress.overdueCount()));
            }
        }
        operators.sort(Comparator.comparingInt(CriticalOperator::overdueCount).reversed());
        return operators;
    }

    /**
     * Mirrors get_group_users:777-781: which of {@code candidates} actually
     * belong to the given whitelist-bucket department and exact group label
     * -- the same {@link DepartmentMatcher}/{@link DepartmentBuckets} rule
     * {@link DepartmentStatsBuilder} uses, per that function's own comment
     * ("the same single source of truth... rather than a second,
     * hand-maintained filter").
     */
    public static List<User> filterUsersInGroup(List<User> candidates, String department, String groupName) {
        List<User> matched = new ArrayList<>();
        for (User user : candidates) {
            DepartmentGroup group = DepartmentMatcher.splitGroup(user.getDepartment());
            if (Objects.equals(department, DepartmentBuckets.match(group.prefix()))
                    && Objects.equals(groupName, group.groupLabel())) {
                matched.add(user);
            }
        }
        return matched;
    }

    /** Mirrors get_group_users:796-809: completion list sorted ascending (lowest first, for the drill-down modal). */
    public static List<GroupMemberCompletion> buildGroupUserCompletions(List<ComplianceRecord> records) {
        List<GroupMemberCompletion> result = new ArrayList<>();
        for (ComplianceRecord record : records) {
            String[] parts = DisplayName.splitFirstLast(record.user().getName());
            result.add(new GroupMemberCompletion(
                    record.user().getId(),
                    DisplayName.firstName(parts),
                    DisplayName.lastName(parts),
                    record.progress().percentage()));
        }
        result.sort(Comparator.comparingInt(GroupMemberCompletion::completionPercentage));
        return result;
    }
}
