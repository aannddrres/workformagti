package ge.magti.portal.stats;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentGroup;
import ge.magti.portal.util.DepartmentMatcher;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ports the DB-free half of routers/stats.py's build_department_stats
 * (routers/stats.py:573-665): given already-computed per-user compliance
 * records, groups them into the Department -&gt; Group -&gt; Member tree the
 * executive dashboard renders. The DB query half
 * ({@code compute_compliance(db)}, which produces the
 * {@link ComplianceRecord} list this takes as input) is not ported --
 * needs a repository.
 *
 * <p>Users whose department doesn't match any of
 * {@link DepartmentBuckets#WHITELIST} are silently excluded, matching
 * Python exactly (routers/stats.py:595-597) -- not an error, just outside
 * the three tracked service lines.
 *
 * <p>Rounding uses {@link Math#rint(double)}, not {@code Math.round}, for
 * the same round-half-to-even reason as {@link ComplianceCalculator} --
 * Python's plain {@code round()} is used here too (routers/stats.py:569).
 */
public final class DepartmentStatsBuilder {

    private DepartmentStatsBuilder() {
    }

    public static DepartmentDashboard build(List<ComplianceRecord> records, OffsetDateTime generatedAt) {
        Map<String, Map<String, List<DepartmentMember>>> groupsByDept = new LinkedHashMap<>();
        for (String bucket : DepartmentBuckets.WHITELIST) {
            groupsByDept.put(bucket, new LinkedHashMap<>());
        }
        List<DepartmentMember> allMembers = new ArrayList<>();

        for (ComplianceRecord record : records) {
            User user = record.user();
            DepartmentGroup group = DepartmentMatcher.splitGroup(user.getDepartment());
            String matched = DepartmentBuckets.match(group.prefix());
            if (matched == null) {
                continue;
            }

            DepartmentMember member = new DepartmentMember(
                    user.getId(),
                    user.getName(),
                    user.getPosition(),
                    record.progress().readCount(),
                    record.progress().requiredCount(),
                    record.progress().percentage(),
                    record.progress().requiredCount() > 0
                            && record.progress().percentage() < ComplianceCalculator.CRITICAL_THRESHOLD);

            groupsByDept.get(matched)
                    .computeIfAbsent(group.groupLabel(), key -> new ArrayList<>())
                    .add(member);
            allMembers.add(member);
        }

        List<DepartmentStats> departments = new ArrayList<>();
        for (String bucket : DepartmentBuckets.WHITELIST) {
            List<DepartmentGroupStats> groups = new ArrayList<>();
            for (Map.Entry<String, List<DepartmentMember>> entry : groupsByDept.get(bucket).entrySet()) {
                String groupLabel = entry.getKey();
                List<DepartmentMember> members = new ArrayList<>(entry.getValue());
                members.sort(Comparator.comparingInt(DepartmentMember::percentage).reversed());
                Rollup rollup = aggregate(members);
                groups.add(new DepartmentGroupStats(
                        groupLabel,
                        fullDepartment(bucket, groupLabel),
                        members.size(),
                        rollup.compliance(),
                        rollup.outputVolume(),
                        rollup.criticalCount(),
                        members));
            }
            groups.sort(Comparator.comparing(DepartmentGroupStats::name));

            List<DepartmentMember> deptMembers = new ArrayList<>();
            for (DepartmentGroupStats groupStats : groups) {
                deptMembers.addAll(groupStats.members());
            }
            Rollup deptRollup = aggregate(deptMembers);
            departments.add(new DepartmentStats(
                    bucket,
                    deptMembers.size(),
                    groups.size(),
                    deptRollup.compliance(),
                    deptRollup.outputVolume(),
                    deptRollup.criticalCount(),
                    deptMembers.isEmpty(),
                    groups));
        }

        Rollup globalRollup = aggregate(allMembers);
        DashboardInsights insights = new DashboardInsights(
                globalRollup.compliance(), globalRollup.criticalCount(), globalRollup.outputVolume(), allMembers.size());

        return new DepartmentDashboard(insights, departments, generatedAt);
    }

    /**
     * The same dashboard with every group's {@code members} list emptied --
     * the aggregate view, with no per-person rows.
     *
     * <p><b>SEC-03 fix (audit OPUS5-1), option (a), user-decided
     * 2026-08-14:</b> {@code /api/manager/department-stats} is org-wide for
     * managers by design (it is the Executive Department Dashboard, and
     * {@code StatsController.getCriticalOperators}' javadoc records that its
     * whole audience is managers), but the payload carried
     * {@link DepartmentMember} rows -- user_id, user_name, position,
     * percentage, is_critical -- for EVERY department, to any manager. The
     * decision was to keep the org-wide aggregates and drop the named rows,
     * rather than scoping the whole computation: the leak is the per-person
     * list, and a department's overall percentage is not personal data.
     *
     * <p>Only the response is redacted, never the computation -- {@code
     * compliance}, {@code output_volume}, {@code critical_count} and {@code
     * member_count} are all derived from the full member list in
     * {@link #build} before this runs, so every number a manager sees stays
     * exactly what it was. A non-zero {@code member_count} beside an empty
     * {@code members} is therefore a redaction marker, not an empty group.
     *
     * <p>Emptied rather than nulled so the wire shape stays a JSON array and
     * the Angular {@code DepartmentGroupStats.members: DepartmentMember[]}
     * type stays honest (the frontend never reads it -- the group drill-down
     * uses the separately-scoped {@code getGroupUsers} endpoint instead --
     * but an absent key would make that declared type a lie).
     */
    public static DepartmentDashboard withoutMembers(DepartmentDashboard dashboard) {
        List<DepartmentStats> redacted = dashboard.departments().stream()
                .map(dept -> new DepartmentStats(
                        dept.name(), dept.memberCount(), dept.groupCount(), dept.compliance(),
                        dept.outputVolume(), dept.criticalCount(), dept.empty(),
                        dept.groups().stream()
                                .map(group -> group.withMembers(List.of()))
                                .toList()))
                .toList();
        return new DepartmentDashboard(dashboard.insights(), redacted, dashboard.generatedAt());
    }

    private record Rollup(int compliance, int outputVolume, int criticalCount) {
    }

    /**
     * Mirrors routers/stats.py's _aggregate_members (routers/stats.py:560-570):
     * the compliance average excludes members with no required readings, so
     * an operator with nothing assigned doesn't drag the average toward 0.
     */
    private static Rollup aggregate(List<DepartmentMember> members) {
        int outputVolume = 0;
        int criticalCount = 0;
        List<Integer> scored = new ArrayList<>();
        for (DepartmentMember member : members) {
            outputVolume += member.readCount();
            if (member.critical()) {
                criticalCount++;
            }
            if (member.requiredCount() > 0) {
                scored.add(member.percentage());
            }
        }

        int compliance = 0;
        if (!scored.isEmpty()) {
            int sum = 0;
            for (int percentage : scored) {
                sum += percentage;
            }
            compliance = (int) Math.rint(sum / (double) scored.size());
        }
        return new Rollup(compliance, outputVolume, criticalCount);
    }

    /** Mirrors routers/stats.py's _group_full_department (routers/stats.py:668-672). */
    private static String fullDepartment(String prefix, String groupLabel) {
        if (groupLabel != null && !groupLabel.isEmpty() && !groupLabel.equals(prefix)) {
            return prefix + " " + DepartmentMatcher.CANONICAL_DELIMITER + " " + groupLabel;
        }
        return prefix;
    }
}
