package ge.magti.portal.org;

import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.stats.DepartmentBuckets;
import ge.magti.portal.util.DepartmentGroup;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns today's free-text {@code users.department} into the normalised org
 * structure V36 created -- as a plan, without touching anything.
 *
 * <h2>Format-preserving</h2>
 *
 * This never rewrites {@code users.department}. That column stays exactly as
 * it is, and every existing authorization decision keeps reading it unchanged
 * through the whole of Phase 2. The reason is narrow and specific:
 * {@code ComplianceCalculator.computeProgress} keys required-reading counts on
 * that literal string and on {@link DepartmentMatcher#splitGroup}'s prefix of
 * it, so a single normalised character -- an en dash for an em dash, a
 * collapsed space -- silently changes every compliance percentage in the
 * company. The plan calls this out as its own risk; the answer is to add
 * {@code team_id} beside the string rather than to tidy the string.
 *
 * <h2>Why {@link DepartmentBuckets} is still used here</h2>
 *
 * It is the third department mechanism and Phase 4 deletes it. But it is also
 * the only place that encodes what the real data actually looks like -- that
 * truncated prefixes occur in live rows. This planner uses it strictly as the
 * legacy-string normaliser it is: it maps a messy prefix to a canonical name,
 * and the {@code departments} table decides identity from there. Once
 * {@code team_id} is canonical, nothing needs the normaliser and it goes.
 *
 * <h2>Fail-closed leadership</h2>
 *
 * Only an unambiguous group match becomes an assignment. A manager stored as a
 * bare department, one whose department resolves to nothing, and two managers
 * landing on the same group are all reported instead. The asymmetry is the
 * argument: an operator wrongly left without a group sees an empty screen and
 * complains, while a manager wrongly pointed at a group reads a team that is
 * not theirs and nothing complains.
 */
public final class OrgBackfillPlanner {

    /** {@link DepartmentMatcher}'s wildcard: an audience, never an org unit. */
    private static final String WILDCARD = "All";

    private OrgBackfillPlanner() {
    }

    /**
     * @param activeUsers   every active user; inactive rows are left alone entirely
     * @param departments   the three rows V36 seeded, matched by display name
     * @param existingTeams groups that already exist, so a re-run creates nothing twice
     */
    public static OrgBackfillPlan plan(List<User> activeUsers, List<Department> departments, List<Team> existingTeams) {
        Map<String, Long> departmentIdByName = new LinkedHashMap<>();
        for (Department department : departments) {
            departmentIdByName.put(department.getName(), department.getId());
        }

        Set<String> existingTeamKeys = new LinkedHashSet<>();
        for (Team team : existingTeams) {
            if (team.getDepartmentId() != null) {
                existingTeamKeys.add(teamKey(team.getDepartmentId(), team.getName()));
            }
        }

        List<OrgBackfillPlan.TeamToCreate> teamsToCreate = new ArrayList<>();
        List<OrgBackfillPlan.Membership> memberships = new ArrayList<>();
        List<OrgBackfillIssue> issues = new ArrayList<>();
        Set<String> plannedTeamKeys = new LinkedHashSet<>();

        // Manager -> group, collected first and only committed once every
        // manager has been seen: a collision is not visible until the second
        // one arrives, and by then the first has already been "decided".
        Map<String, List<Long>> managersByTeamKey = new LinkedHashMap<>();
        Map<String, Resolved> resolvedByTeamKey = new LinkedHashMap<>();

        for (User user : activeUsers) {
            Resolved resolved = resolve(user);
            boolean isManager = user.getRole() == Role.MANAGER;

            if (resolved.issueKind() != null) {
                issues.add(new OrgBackfillIssue(
                        reportedKind(resolved.issueKind(), isManager),
                        user.getId(), user.getDepartment(), resolved.detail()));
                continue;
            }

            Long departmentId = departmentIdByName.get(resolved.canonicalDepartment());
            if (departmentId == null) {
                issues.add(new OrgBackfillIssue(
                        reportedKind(OrgBackfillIssue.Kind.UNMAPPED_DEPARTMENT, isManager),
                        user.getId(), user.getDepartment(),
                        "department \"" + resolved.canonicalDepartment()
                                + "\" is not seeded in the departments table"));
                continue;
            }

            String key = teamKey(departmentId, resolved.groupName());
            if (!existingTeamKeys.contains(key) && plannedTeamKeys.add(key)) {
                teamsToCreate.add(new OrgBackfillPlan.TeamToCreate(departmentId, resolved.groupName()));
            }
            memberships.add(new OrgBackfillPlan.Membership(user.getId(), departmentId, resolved.groupName()));

            if (isManager) {
                managersByTeamKey.computeIfAbsent(key, k -> new ArrayList<>()).add(user.getId());
                resolvedByTeamKey.put(key, new Resolved(resolved.canonicalDepartment(), resolved.groupName(),
                        null, null, departmentId));
            }
        }

        List<OrgBackfillPlan.LeadershipCandidate> leadership = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : managersByTeamKey.entrySet()) {
            List<Long> managerIds = entry.getValue();
            Resolved resolved = resolvedByTeamKey.get(entry.getKey());
            if (managerIds.size() > 1) {
                // Neither is assigned. Choosing by row order would make the
                // outcome depend on how the query happened to sort, and V36's
                // unique index rejects the second write anyway.
                issues.add(new OrgBackfillIssue(
                        OrgBackfillIssue.Kind.DUPLICATE_PRIMARY, null, resolved.groupName(),
                        managerIds.size() + " managers resolve to this group (user ids " + managerIds
                                + "); a system admin must pick the primary before cutover"));
                continue;
            }
            leadership.add(new OrgBackfillPlan.LeadershipCandidate(
                    managerIds.get(0), resolved.departmentId(), resolved.groupName()));
        }

        return new OrgBackfillPlan(
                List.copyOf(teamsToCreate), List.copyOf(memberships),
                List.copyOf(leadership), List.copyOf(issues));
    }

    /**
     * A manager who cannot be placed is a different problem from an operator
     * who cannot be placed, and the report has to separate them: the operator
     * loses a group, the manager loses a scope over other people's data.
     * {@code DEPARTMENT_ONLY_MANAGER} is already manager-specific and keeps
     * its own name.
     */
    private static OrgBackfillIssue.Kind reportedKind(OrgBackfillIssue.Kind kind, boolean isManager) {
        if (!isManager || kind == OrgBackfillIssue.Kind.DEPARTMENT_ONLY_MANAGER) {
            return kind;
        }
        return OrgBackfillIssue.Kind.UNRESOLVED_MANAGER;
    }

    private record Resolved(
            String canonicalDepartment, String groupName,
            OrgBackfillIssue.Kind issueKind, String detail, Long departmentId) {

        static Resolved ok(String canonicalDepartment, String groupName) {
            return new Resolved(canonicalDepartment, groupName, null, null, null);
        }

        static Resolved problem(OrgBackfillIssue.Kind kind, String detail) {
            return new Resolved(null, null, kind, detail, null);
        }
    }

    private static Resolved resolve(User user) {
        String raw = user.getDepartment();
        if (raw == null || raw.isBlank()) {
            return Resolved.problem(OrgBackfillIssue.Kind.NO_DEPARTMENT,
                    "users.department is empty; no department and no group can be derived");
        }
        if (WILDCARD.equals(raw.strip())) {
            return Resolved.problem(OrgBackfillIssue.Kind.WILDCARD_DEPARTMENT,
                    "the wildcard is an audience, not an organisational unit");
        }

        DepartmentGroup group = DepartmentMatcher.splitGroup(raw);
        String canonical = DepartmentBuckets.match(group.prefix());
        if (canonical == null) {
            return Resolved.problem(OrgBackfillIssue.Kind.UNMAPPED_DEPARTMENT,
                    "prefix \"" + group.prefix() + "\" matches none of the three official departments");
        }

        // splitGroup returns (prefix, prefix) when there is no group suffix at
        // all -- for a bare department name, and also for a trailing dash with
        // nothing after it. Equality is therefore the "has no group" signal.
        if (group.prefix().equals(group.groupLabel())) {
            return Resolved.problem(OrgBackfillIssue.Kind.DEPARTMENT_ONLY_MANAGER,
                    "names department \"" + canonical + "\" but no group");
        }
        return Resolved.ok(canonical, group.groupLabel());
    }

    /** V37 makes (department_id, name) the uniqueness rule; the planner keys on it early. */
    private static String teamKey(Long departmentId, String name) {
        return departmentId + " " + name;
    }
}
