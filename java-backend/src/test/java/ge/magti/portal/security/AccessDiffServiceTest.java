package ge.magti.portal.security;

import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.web.AccessDiffResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessDiffServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final LeadershipAssignmentRepository assignments = mock(LeadershipAssignmentRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final AccessDiffService service = new AccessDiffService(users, assignments, teams);

    private static User user(long id, Role role, String department, Long teamId) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        user.setRole(role);
        user.setDepartment(department);
        user.setTeamId(teamId);
        user.setActive(true);
        return user;
    }

    private static LeadershipAssignment leads(long userId, long teamId) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setId(100L + userId);
        assignment.setUserId(userId);
        assignment.setTeamId(teamId);
        assignment.setAssignmentType(AssignmentType.ACTING);
        assignment.setActive(true);
        return assignment;
    }

    private static Team team(long id, long departmentId) {
        Team team = new Team();
        team.setId(id);
        team.setDepartmentId(departmentId);
        team.setName("team-" + id);
        team.setActive(true);
        return team;
    }

    @Test
    void reportClassifiesEveryUserAndReturnsOnlyChangedRows() {
        User actingOperator = user(1L, Role.OPERATOR, "ტექნიკური — ჯგუფი 01", 10L);
        User unassignedManager = user(2L, Role.MANAGER, "ტექნიკური", null);
        User forcedInContentAdmin = user(3L, Role.CONTENT_ADMIN, "ოფისი", null);
        forcedInContentAdmin.setComplianceOverride(true);
        User unchangedAdmin = user(4L, Role.SYSTEM_ADMIN, "All", null);
        User inactiveOperator = user(5L, Role.OPERATOR, "ტექნიკური", 10L);
        inactiveOperator.setActive(false);
        when(users.findAll()).thenReturn(List.of(
                actingOperator, unassignedManager, forcedInContentAdmin, unchangedAdmin, inactiveOperator));
        when(assignments.findByActiveTrue()).thenReturn(List.of(leads(1L, 10L)));
        when(teams.findAll()).thenReturn(List.of(team(10L, 5L)));

        AccessDiffResponse result = service.report();

        assertNotNull(result.generatedAt());
        assertEquals(5, result.totals().users(), "totals counts inactive users too");
        assertEquals(1, result.totals().gains());
        assertEquals(2, result.totals().losses());
        assertEquals(2, result.totals().unchanged());
        assertEquals(List.of(1L, 2L, 3L), result.rows().stream().map(row -> row.userId()).toList());

        var operator = result.rows().get(0);
        assertEquals(true, operator.compliance().legacy());
        assertEquals(false, operator.compliance().proposed());
        assertEquals(0, operator.scope().legacyUserCount());
        assertEquals(1, operator.scope().proposedUserCount());

        var manager = result.rows().get(1);
        assertEquals(2, manager.scope().legacyUserCount());
        assertEquals(0, manager.scope().proposedUserCount());
    }

    @Test
    void generatingTheReportNeverMutatesAnyRepository() {
        when(users.findAll()).thenReturn(List.of());
        when(assignments.findByActiveTrue()).thenReturn(List.of());
        when(teams.findAll()).thenReturn(List.of());

        service.report();

        verify(users, never()).save(any(User.class));
        verify(users, never()).delete(any(User.class));
        verify(users, never()).deleteAll();
        verify(assignments, never()).save(any(LeadershipAssignment.class));
        verify(assignments, never()).delete(any(LeadershipAssignment.class));
        verify(assignments, never()).deleteAll();
        verify(teams, never()).save(any(Team.class));
        verify(teams, never()).delete(any(Team.class));
        verify(teams, never()).deleteAll();
    }
}
