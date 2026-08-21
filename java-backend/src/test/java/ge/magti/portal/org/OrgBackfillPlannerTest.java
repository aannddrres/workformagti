package ge.magti.portal.org;

import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 2 backfill, asserted without a database.
 *
 * <p>Two properties matter more than the rest, and both are things a
 * migration gets wrong quietly rather than loudly:
 *
 * <ol>
 *   <li><b>It does not touch {@code users.department}.</b> Compliance
 *       percentages are computed from that literal string, so normalising one
 *       dash changes every percentage in the company with no error anywhere.
 *   <li><b>It refuses to guess a leader.</b> A manager placed on the wrong
 *       group reads another team's personal data and nothing complains, so
 *       every ambiguous case has to end up in the report instead of in the
 *       database.
 * </ol>
 */
class OrgBackfillPlannerTest {

    private static final String TECHNICAL = "ტექნიკური";
    private static final String OFFICE = "ოფისი";

    private static Department department(long id, String name) {
        Department department = new Department();
        department.setId(id);
        department.setName(name);
        return department;
    }

    private static final List<Department> DEPARTMENTS = List.of(
            department(1L, TECHNICAL), department(2L, "საინფორმაციო"), department(3L, OFFICE));

    private static User user(long id, Role role, String department) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        return user;
    }

    private static OrgBackfillPlan planFor(List<User> users) {
        return OrgBackfillPlanner.plan(users, DEPARTMENTS, List.of());
    }

    private static List<OrgBackfillIssue.Kind> kinds(OrgBackfillPlan plan) {
        return plan.issues().stream().map(OrgBackfillIssue::kind).toList();
    }

    // ---- the mapping itself ---------------------------------------------

    @Test
    void aGroupedDepartmentBecomesAGroupAndAMembership() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 03"),
                user(2L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 03"),
                user(3L, Role.OPERATOR, OFFICE + " — ჯგუფი 01")));

        assertEquals(
                List.of(new OrgBackfillPlan.TeamToCreate(1L, "ჯგუფი 03"),
                        new OrgBackfillPlan.TeamToCreate(3L, "ჯგუფი 01")),
                plan.teamsToCreate(),
                "one group per distinct (department, label), not one per user");
        assertEquals(3, plan.memberships().size());
        assertTrue(plan.issues().isEmpty());
    }

    /**
     * The same group label under two departments is two groups. This is the
     * case the old global {@code uq_teams_name} made impossible and V36 drops
     * the constraint for.
     */
    @Test
    void theSameGroupNameUnderTwoDepartmentsIsTwoGroups() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 01"),
                user(2L, Role.OPERATOR, OFFICE + " — ჯგუფი 01")));

        assertEquals(
                List.of(new OrgBackfillPlan.TeamToCreate(1L, "ჯგუფი 01"),
                        new OrgBackfillPlan.TeamToCreate(3L, "ჯგუფი 01")),
                plan.teamsToCreate());
    }

    /** A re-run must add nothing: the backfill is run repeatedly while the report is worked through. */
    @Test
    void groupsThatAlreadyExistAreNotPlannedAgain() {
        Team existing = new Team();
        existing.setId(10L);
        existing.setName("ჯგუფი 03");
        existing.setDepartmentId(1L);

        OrgBackfillPlan plan = OrgBackfillPlanner.plan(
                List.of(user(1L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 03")), DEPARTMENTS, List.of(existing));

        assertTrue(plan.teamsToCreate().isEmpty());
        assertEquals(1, plan.memberships().size(), "the membership is still planned, only the group is not recreated");
    }

    /**
     * The truncated prefixes {@code DepartmentBuckets} exists for. Live rows
     * carry them, and a mapper that only matched the full names would drop
     * those people into the report instead of into their department.
     */
    @Test
    void truncatedLegacyPrefixesStillResolve() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.OPERATOR, "ტექნიკურ — ჯგუფი 02"),
                user(2L, Role.OPERATOR, "საინფო — ჯგუფი 05")));

        assertTrue(plan.issues().isEmpty(), "legacy prefix spellings must map, not land in the report");
        assertEquals(
                List.of(1L, 2L),
                plan.memberships().stream().map(OrgBackfillPlan.Membership::departmentId).toList(),
                "the truncated spellings must resolve to the full departments, not to new ones");
    }

    // ---- format preservation --------------------------------------------

    /**
     * The property the plan calls out as its own risk. Asserted on the object
     * the planner was handed, because the planner having no writer of its own
     * is exactly what makes that safe -- and a future refactor that gave it
     * one would fail here.
     */
    @Test
    void theFreeTextDepartmentStringIsNeverRewritten() {
        String awkward = "  ტექნიკური   -  ჯგუფი 03 ";
        User operator = user(1L, Role.OPERATOR, awkward);

        planFor(List.of(operator));

        assertEquals(awkward, operator.getDepartment(),
                "compliance percentages are keyed on this exact string; normalising it changes them silently");
    }

    /** And the group it derives still has to be the one the old rule derives. */
    @Test
    void theDerivedGroupMatchesWhatTheLegacyRuleAlreadySees() {
        String stored = "ტექნიკური — ჯგუფი 03";
        OrgBackfillPlan plan = planFor(List.of(user(1L, Role.OPERATOR, stored)));

        assertEquals(DepartmentMatcher.splitGroup(stored).groupLabel(), plan.memberships().get(0).groupName());
    }

    // ---- fail-closed leadership -----------------------------------------

    @Test
    void aManagerWithAnUnambiguousGroupBecomesItsPrimaryLeader() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.MANAGER, TECHNICAL + " — ჯგუფი 03"),
                user(2L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 03")));

        assertEquals(
                List.of(new OrgBackfillPlan.LeadershipCandidate(1L, 1L, "ჯგუფი 03")),
                plan.leadership());
        assertTrue(plan.issues().isEmpty());
    }

    /**
     * A department head is in the data model but explicitly not in the first
     * rollout, so a missing group suffix must not quietly promote someone to
     * every group in their department.
     */
    @Test
    void aManagerWithNoGroupIsReportedRatherThanMadeADepartmentHead() {
        OrgBackfillPlan plan = planFor(List.of(user(1L, Role.MANAGER, TECHNICAL)));

        assertTrue(plan.leadership().isEmpty());
        assertEquals(List.of(OrgBackfillIssue.Kind.DEPARTMENT_ONLY_MANAGER), kinds(plan));
    }

    @Test
    void twoManagersOnOneGroupLeaveBothUnassigned() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.MANAGER, TECHNICAL + " — ჯგუფი 03"),
                user(2L, Role.MANAGER, TECHNICAL + " — ჯგუფი 03")));

        assertTrue(plan.leadership().isEmpty(),
                "picking one by row order would make the outcome depend on query ordering");
        assertEquals(List.of(OrgBackfillIssue.Kind.DUPLICATE_PRIMARY), kinds(plan));
        assertTrue(plan.issues().get(0).detail().contains("[1, 2]"), "the report has to name both");
    }

    /** Both are still placed in the group -- the collision is about who leads it, not who is in it. */
    @Test
    void aDuplicatePrimaryDoesNotCostAnybodyTheirMembership() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.MANAGER, TECHNICAL + " — ჯგუფი 03"),
                user(2L, Role.MANAGER, TECHNICAL + " — ჯგუფი 03")));

        assertEquals(2, plan.memberships().size());
    }

    @Test
    void anUnplaceableManagerIsDistinguishedFromAnUnplaceableOperator() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.MANAGER, "მარკეტინგი — ჯგუფი 01"),
                user(2L, Role.OPERATOR, "მარკეტინგი — ჯგუფი 01")));

        assertEquals(
                List.of(OrgBackfillIssue.Kind.UNRESOLVED_MANAGER, OrgBackfillIssue.Kind.UNMAPPED_DEPARTMENT),
                kinds(plan),
                "a manager losing a scope over other people is not the same finding as an operator losing a group");
    }

    // ---- the values that mean "no org unit" ------------------------------

    @Test
    void theWildcardAndTheEmptyStringAreReportedNotMapped() {
        OrgBackfillPlan plan = planFor(List.of(
                user(1L, Role.OPERATOR, "All"),
                user(2L, Role.OPERATOR, null),
                user(3L, Role.OPERATOR, "   ")));

        assertTrue(plan.memberships().isEmpty());
        assertEquals(
                List.of(OrgBackfillIssue.Kind.WILDCARD_DEPARTMENT,
                        OrgBackfillIssue.Kind.NO_DEPARTMENT,
                        OrgBackfillIssue.Kind.NO_DEPARTMENT),
                kinds(plan));
    }

    /** The cutover gate: V37 and Phase 4 both wait on this being false. */
    @Test
    void anyUnresolvedIssueBlocksTheCutover() {
        assertFalse(planFor(List.of(user(1L, Role.OPERATOR, TECHNICAL + " — ჯგუფი 03"))).hasUnresolvedIssues());
        assertTrue(planFor(List.of(user(1L, Role.MANAGER, TECHNICAL))).hasUnresolvedIssues());
    }

    @Test
    void theReportNamesEveryCategoryItFound() {
        String report = OrgBackfillService.report(planFor(List.of(
                user(1L, Role.MANAGER, TECHNICAL),
                user(2L, Role.OPERATOR, "All"))));

        assertTrue(report.contains("DEPARTMENT_ONLY_MANAGER"), report);
        assertTrue(report.contains("WILDCARD_DEPARTMENT"), report);
        assertTrue(report.contains("V37 must NOT be applied"), report);
    }
}
