package ge.magti.portal.security;

import ge.magti.portal.compliance.ComplianceEligibilityService;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Phase 3 policy layer: the three rules that will replace role-derived
 * authorization, plus the shadow harness that measures them before they decide
 * anything.
 *
 * <p>What each rule answers is deliberately separate here, because conflating
 * them is what the old model did: {@link ScopeResolver} answers "whose data",
 * {@link CapabilityService} answers "what actions", and eligibility answers
 * "who must read". A permission widening a scope is the specific defect the
 * plan forbids, and it has its own test below.
 */
class PolicyLayerTest {

    private final LeadershipAssignmentRepository assignments = mock(LeadershipAssignmentRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final UserPermissionOverrideRepository overrides = mock(UserPermissionOverrideRepository.class);
    private final PolicyShadowRecorder recorder = new PolicyShadowRecorder();

    private final ScopeResolver scopeResolver = new ScopeResolver(assignments, teams, recorder);
    private final CapabilityService capabilities = new CapabilityService(overrides, recorder);
    private final ComplianceEligibilityService eligibility =
            new ComplianceEligibilityService(assignments, recorder);

    private static User user(long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setActive(true);
        return user;
    }

    private static User member(long id, Long teamId) {
        User user = user(id, Role.OPERATOR);
        user.setTeamId(teamId);
        return user;
    }

    private static LeadershipAssignment leads(long userId, Long teamId, Long departmentId) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(userId);
        assignment.setTeamId(teamId);
        assignment.setDepartmentId(departmentId);
        assignment.setAssignmentType(AssignmentType.PRIMARY);
        assignment.setActive(true);
        return assignment;
    }

    private static Team team(long id, long departmentId) {
        Team team = new Team();
        team.setId(id);
        team.setDepartmentId(departmentId);
        team.setName("group-" + id);
        return team;
    }

    // ---- ScopeResolver ---------------------------------------------------

    @Test
    void noAssignmentMeansNobody() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());

        Scope scope = scopeResolver.resolve(user(1L, Role.MANAGER));

        assertTrue(scope.readsNobody(),
                "the MANAGER role on its own must stop granting a scope -- that is the whole change");
        assertFalse(scope.unscoped());
    }

    @Test
    void onlySystemAdminIsUnscoped() {
        assertTrue(scopeResolver.resolve(user(1L, Role.SYSTEM_ADMIN)).unscoped());
        for (Role role : List.of(Role.MANAGER, Role.CONTENT_ADMIN, Role.OPERATOR)) {
            when(assignments.findByUserIdAndActiveTrue(2L)).thenReturn(List.of());
            assertFalse(scopeResolver.resolve(user(2L, role)).unscoped(), role + " must never be unscoped");
        }
    }

    @Test
    void aGroupAssignmentCoversThatGroupAndNoSibling() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(leads(1L, 10L, null)));

        List<User> visible = scopeResolver.visibleUsers(
                List.of(member(2L, 10L), member(3L, 11L), member(4L, null)),
                user(1L, Role.MANAGER));

        assertEquals(List.of(2L), visible.stream().map(User::getId).toList(),
                "a sibling group and an unplaced user are both outside the scope");
    }

    /** Rule §2: one person may temporarily lead several groups, and sees exactly those. */
    @Test
    void twoGroupAssignmentsCoverBothAndStillNotAThird() {
        when(assignments.findByUserIdAndActiveTrue(1L))
                .thenReturn(List.of(leads(1L, 10L, null), leads(1L, 12L, null)));

        List<User> visible = scopeResolver.visibleUsers(
                List.of(member(2L, 10L), member(3L, 11L), member(4L, 12L)), user(1L, Role.MANAGER));

        assertEquals(List.of(2L, 4L), visible.stream().map(User::getId).toList());
    }

    /** A department head sees the department's groups -- expanded once, at resolve time. */
    @Test
    void aDepartmentAssignmentExpandsIntoItsGroups() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(leads(1L, null, 5L)));
        when(teams.findAll()).thenReturn(List.of(team(10L, 5L), team(11L, 5L), team(12L, 6L)));

        Scope scope = scopeResolver.resolve(user(1L, Role.MANAGER));

        assertEquals(Set.of(10L, 11L), scope.teamIds());
        assertEquals(Set.of(5L), scope.departmentIds(), "the department is kept for the access explanation");
        assertFalse(scope.includesTeam(12L), "another department's group stays out");
    }

    @Test
    void firstEvidenceRolloutUsesDirectGroupAssignmentsButNotDepartmentAssignments() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(
                leads(1L, 10L, null),
                leads(1L, null, 5L)));

        Scope scope = scopeResolver.resolveGroupLeadership(user(1L, Role.MANAGER));

        assertEquals(Set.of(10L), scope.teamIds());
        assertTrue(scope.departmentIds().isEmpty());
        verifyNoInteractions(teams);
    }

    /** An unplaced person belongs to nobody's scope -- never to everybody's. */
    @Test
    void aUserWithNoTeamIsNeverInScope() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(leads(1L, 10L, null)));

        assertFalse(scopeResolver.resolve(user(1L, Role.MANAGER)).includesTeam(null));
    }

    @Test
    void anInactiveCallerReadsNobodyEvenAsSystemAdmin() {
        User admin = user(1L, Role.SYSTEM_ADMIN);
        admin.setActive(false);

        assertTrue(scopeResolver.resolve(admin).readsNobody());
    }

    // ---- CapabilityService ----------------------------------------------

    @Test
    void aRoleDefaultAppliesWithNoOverrideRow() {
        when(overrides.findByUserId(1L)).thenReturn(List.of());

        assertTrue(capabilities.hasCapability(user(1L, Role.MANAGER), Permission.REPORTS_EXPORT));
        assertFalse(capabilities.hasCapability(user(1L, Role.OPERATOR), Permission.REPORTS_EXPORT));
    }

    /**
     * DENY is the half a flat permission list cannot express: taking a default
     * away from one person without changing their role.
     */
    @Test
    void anExplicitDecisionBeatsTheRoleInBothDirections() {
        assertTrue(CapabilityService.resolve(user(1L, Role.OPERATOR), Permission.ARTICLES_EDIT,
                List.of(override(Permission.ARTICLES_EDIT, UserPermissionOverride.State.ALLOW))));
        assertFalse(CapabilityService.resolve(user(1L, Role.MANAGER), Permission.REPORTS_EXPORT,
                List.of(override(Permission.REPORTS_EXPORT, UserPermissionOverride.State.DENY))));
    }

    /** An override for a different permission must not leak onto this one. */
    @Test
    void anOverrideAppliesOnlyToItsOwnPermission() {
        assertFalse(CapabilityService.resolve(user(1L, Role.OPERATOR), Permission.REPORTS_EXPORT,
                List.of(override(Permission.ARTICLES_EDIT, UserPermissionOverride.State.ALLOW))));
    }

    /**
     * Plan §8, stated as a test because it is the rule the whole model rests
     * on: an action permission never produces a scope over other employees.
     */
    @Test
    void grantingAnExportPermissionCreatesNoScope() {
        when(overrides.findByUserId(1L)).thenReturn(
                List.of(override(Permission.REPORTS_EXPORT, UserPermissionOverride.State.ALLOW)));
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());
        User operator = user(1L, Role.OPERATOR);

        assertTrue(capabilities.hasCapability(operator, Permission.REPORTS_EXPORT), "may run an export");
        assertTrue(scopeResolver.resolve(operator).readsNobody(), "over nobody's data");
    }

    private static UserPermissionOverride override(Permission permission, UserPermissionOverride.State state) {
        UserPermissionOverride row = new UserPermissionOverride();
        row.setPermission(permission.value());
        row.setState(state);
        return row;
    }

    // ---- ComplianceEligibilityService ------------------------------------

    /** The case the new rule exists for: content ability without leaving compliance. */
    @Test
    void anOperatorWithContentPermissionsStaysInCompliance() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());

        assertTrue(eligibility.isEligible(user(1L, Role.OPERATOR)));
    }

    @Test
    void managementRolesAndActiveLeadersAreOut() {
        for (Role role : List.of(Role.MANAGER, Role.CONTENT_ADMIN, Role.SYSTEM_ADMIN)) {
            assertFalse(ComplianceEligibilityService.resolve(user(1L, role), false), role + " is out by role");
        }
        assertFalse(ComplianceEligibilityService.resolve(user(1L, Role.OPERATOR), true),
                "an operator acting as a group leader is out for the duration");
    }

    @Test
    void anAdministratorsOverrideWinsOverBothRoleAndLeadership() {
        User forcedIn = user(1L, Role.CONTENT_ADMIN);
        forcedIn.setComplianceOverride(true);
        assertTrue(ComplianceEligibilityService.resolve(forcedIn, true));

        User forcedOut = user(2L, Role.OPERATOR);
        forcedOut.setComplianceOverride(false);
        assertFalse(ComplianceEligibilityService.resolve(forcedOut, false));
    }

    @Test
    void anInactiveUserIsNeverEligibleWhateverTheOverrideSays() {
        User user = user(1L, Role.OPERATOR);
        user.setActive(false);
        user.setComplianceOverride(true);

        assertFalse(ComplianceEligibilityService.resolve(user, false));
    }

    // ---- the shadow harness ----------------------------------------------

    /**
     * The property that makes shadow mode safe: the caller always receives the
     * legacy answer, however loudly the new rule disagrees.
     */
    @Test
    void shadowComparisonNeverChangesTheAnswerItReturns() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());
        User manager = user(1L, Role.MANAGER);
        List<User> legacy = List.of(member(2L, 10L));

        List<User> served = scopeResolver.shadowCompare("scope.test", manager, legacy, legacy);

        assertEquals(legacy, served);
        assertEquals(1, recorder.snapshot().get("scope.test").disagreed(),
                "the new rule resolves to nobody here, and that disagreement is the measurement");
    }

    @Test
    void agreementsAreCountedTooSoAnUnexercisedDecisionIsVisible() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(leads(1L, 10L, null)));
        List<User> both = List.of(member(2L, 10L));

        scopeResolver.shadowCompare("scope.agreeing", user(1L, Role.MANAGER), both, both);

        PolicyShadowRecorder.Counts counts = recorder.snapshot().get("scope.agreeing");
        assertEquals(new PolicyShadowRecorder.Counts(1, 0), counts);
        assertFalse(counts.unexercised());
        assertTrue(new PolicyShadowRecorder.Counts(0, 0).unexercised(),
                "zero of both is unexercised, which is not the same as clean");
    }

    /** A measurement must not be able to take down the request it measures. */
    @Test
    void aFailingShadowComparisonStillServesTheLegacyAnswer() {
        when(assignments.findByUserIdAndActiveTrue(any()))
                .thenThrow(new IllegalStateException("repository is down"));
        List<User> legacy = List.of(member(2L, 10L));

        List<User> served = scopeResolver.shadowCompare("scope.broken", user(1L, Role.MANAGER), legacy, legacy);

        assertEquals(legacy, served);
        assertEquals(1, recorder.snapshot().get("scope.broken.error").disagreed());
    }

    /** Phase 9A installs switches, but Phase 4/5 alone may wire them into enforcement. */
    @Test
    void rolloutSwitchValuesDoNotChangeEitherShadowReturnValue() {
        when(assignments.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());
        User manager = user(1L, Role.MANAGER);
        List<User> legacyScope = List.of(member(2L, 10L));

        for (boolean enabled : List.of(false, true)) {
            PortalProperties properties = new PortalProperties();
            properties.getRollout().setLeadershipScopeEnabled(enabled);
            properties.getRollout().setComplianceEligibilityEnabled(enabled);

            assertEquals(legacyScope,
                    scopeResolver.shadowCompare("scope.flag." + enabled, manager, legacyScope, legacyScope));
            assertTrue(eligibility.shadowCompare(user(1L, Role.OPERATOR), true));
        }
    }

    // ---- effectivePermissions --------------------------------------------
    //
    // The list a response ships has to be the list the gates use. Before the
    // Phase 6 cutover those were the same object; afterwards users.permissions
    // decides nothing, so a response built from it can disagree in both
    // directions -- and an administrator reading a permission screen has no
    // way to tell which of the two they are looking at.

    private static UserPermissionOverride override(String permission, UserPermissionOverride.State state) {
        UserPermissionOverride row = new UserPermissionOverride();
        row.setPermission(permission);
        row.setState(state);
        return row;
    }

    @Test
    void theEffectiveSetIsTheRoleDefaultWhenNothingIsOverridden() {
        assertEquals(
                Permission.defaultsFor(Role.CONTENT_ADMIN),
                CapabilityService.effectivePermissions(user(1L, Role.CONTENT_ADMIN), List.of()));
        assertTrue(CapabilityService.effectivePermissions(user(2L, Role.OPERATOR), List.of()).isEmpty());
    }

    @Test
    void anExplicitAllowAppearsAndAnExplicitDenyDisappears() {
        Set<Permission> granted = CapabilityService.effectivePermissions(
                user(1L, Role.OPERATOR), List.of(override("content.manage", UserPermissionOverride.State.ALLOW)));
        assertEquals(Set.of(Permission.CONTENT_MANAGE), granted);

        Set<Permission> denied = CapabilityService.effectivePermissions(
                user(2L, Role.CONTENT_ADMIN), List.of(override("content.manage", UserPermissionOverride.State.DENY)));
        assertFalse(denied.contains(Permission.CONTENT_MANAGE));
        assertTrue(denied.contains(Permission.ARTICLES_EDIT), "a DENY takes away one permission, not the role");
    }

    /**
     * The regression this exists for. A role change no longer rewrites
     * {@code users.permissions}, so the column outlives the role that filled
     * it; and an explicit grant was never written there at all.
     */
    @Test
    void theLegacyColumnIsIgnoredInBothDirections() {
        User staleGrant = user(1L, Role.OPERATOR);
        staleGrant.setPermissions(new java.util.LinkedHashSet<>(List.of("articles.edit", "content.manage")));
        assertTrue(CapabilityService.effectivePermissions(staleGrant, List.of()).isEmpty(),
                "a leftover row from a previous role must not read as a permission");

        User quietGrant = user(2L, Role.OPERATOR);
        assertEquals(
                Set.of(Permission.ARTICLES_EDIT),
                CapabilityService.effectivePermissions(
                        quietGrant, List.of(override("articles.edit", UserPermissionOverride.State.ALLOW))),
                "an explicit ALLOW never reaches the legacy column, so it must not be read from there");
    }

    @Test
    void aSystemAdminHoldsEverythingWithoutTouchingTheDatabase() {
        assertEquals(
                Set.copyOf(List.of(Permission.values())),
                capabilities.effectivePermissions(user(1L, Role.SYSTEM_ADMIN)));
        verifyNoInteractions(overrides);
    }

    /** The checker must answer the same as the service, including the bypass. */
    @Test
    void theCheckerAndTheServiceAgree() {
        PermissionChecker checker = new PermissionChecker(capabilities);
        User operator = user(1L, Role.OPERATOR);
        when(overrides.findByUserId(1L))
                .thenReturn(List.of(override("content.manage", UserPermissionOverride.State.ALLOW)));

        Set<Permission> effective = checker.effectivePermissions(operator);

        assertEquals(Set.of(Permission.CONTENT_MANAGE), effective);
        for (Permission permission : Permission.values()) {
            assertEquals(effective.contains(permission), checker.hasPermission(operator, permission),
                    permission.value() + " must be listed exactly when the gate would let it through");
        }
    }
}
