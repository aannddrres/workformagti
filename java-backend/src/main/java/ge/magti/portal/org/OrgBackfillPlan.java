package ge.magti.portal.org;

import java.util.List;

/**
 * What the backfill would do, computed before anything is written.
 *
 * <p>Separating the decision from the write is what makes the dry run
 * meaningful: the same plan object is either printed as a reconciliation
 * report or applied, so the report cannot describe something other than what
 * running it would do.
 *
 * @param teamsToCreate  groups implied by the free-text departments that do not exist as rows yet
 * @param memberships    which user belongs to which group
 * @param leadership     the PRIMARY group assignments that are unambiguous enough to create
 * @param issues         everything the backfill refused to decide -- see {@link OrgBackfillIssue}
 */
public record OrgBackfillPlan(
        List<TeamToCreate> teamsToCreate,
        List<Membership> memberships,
        List<LeadershipCandidate> leadership,
        List<OrgBackfillIssue> issues) {

    /** A group to create, identified the way V37 will make unique: (department, name). */
    public record TeamToCreate(Long departmentId, String name) {
    }

    /** {@code users.team_id = <the team for (departmentId, groupName)>}. Nothing else on the user changes. */
    public record Membership(Long userId, Long departmentId, String groupName) {
    }

    /** One active PRIMARY assignment over a group. */
    public record LeadershipCandidate(Long userId, Long departmentId, String groupName) {
    }

    /** True when a system admin still has rows to resolve -- the Phase 4 cutover gate (plan §8). */
    public boolean hasUnresolvedIssues() {
        return !issues.isEmpty();
    }
}
