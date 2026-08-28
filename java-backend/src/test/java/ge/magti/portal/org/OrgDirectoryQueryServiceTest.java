package ge.magti.portal.org;

import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Team;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrgDirectoryQueryServiceTest {

    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final LeadershipAssignmentRepository assignments = mock(LeadershipAssignmentRepository.class);
    private final OrgDirectoryQueryService service =
            new OrgDirectoryQueryService(departments, teams, assignments);

    @Test
    void exactCeilingIsAcceptedAndEveryQueryRequestsOneSentinelRow() {
        List<Team> rows = Collections.nCopies(OrgDirectoryQueryService.MAX_ROWS, new Team());
        when(teams.findAllByOrderByName(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable page = invocation.getArgument(0);
            assertEquals(OrgDirectoryQueryService.MAX_ROWS + 1, page.getPageSize());
            return rows;
        });

        assertEquals(rows, service.listTeamsWithinLimit());
    }

    @Test
    void everyCompleteResultFamilyFailsLoudlyOnTheSentinelRow() {
        List<Department> departmentRows = Collections.nCopies(
                OrgDirectoryQueryService.MAX_ROWS + 1, new Department());
        List<Team> teamRows = Collections.nCopies(
                OrgDirectoryQueryService.MAX_ROWS + 1, new Team());
        List<LeadershipAssignment> assignmentRows = Collections.nCopies(
                OrgDirectoryQueryService.MAX_ROWS + 1, new LeadershipAssignment());
        when(departments.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(departmentRows));
        when(departments.findByActiveTrueOrderBySortOrder(any(Pageable.class))).thenReturn(departmentRows);
        when(teams.findAllByOrderByName(any(Pageable.class))).thenReturn(teamRows);
        when(teams.findByDepartmentIdIn(any(), any(Pageable.class))).thenReturn(teamRows);
        when(assignments.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(assignmentRows));
        when(assignments.findByActiveTrue(any(Pageable.class))).thenReturn(assignmentRows);
        when(assignments.findByUserIdAndActiveTrue(any(), any(Pageable.class))).thenReturn(assignmentRows);

        assertAll(
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        service::listDepartmentsWithinLimit),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        service::listActiveDepartmentsWithinLimit),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        service::listTeamsWithinLimit),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        () -> service.listTeamsInDepartmentsWithinLimit(List.of(1L))),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        service::listAssignmentsWithinLimit),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        service::listActiveAssignmentsWithinLimit),
                () -> assertThrows(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class,
                        () -> service.listActiveAssignmentsForUserWithinLimit(7L)));
    }

    @Test
    void emptyDepartmentSelectionDoesNotIssueAnInQuery() {
        assertEquals(List.of(), service.listTeamsInDepartmentsWithinLimit(List.of()));
        verifyNoInteractions(teams);
    }

    @Test
    void legacyPathResolvesOnlyOneActiveCanonicalTeam() {
        Department department = new Department();
        department.setId(7L);
        department.setActive(true);
        Team team = new Team();
        team.setId(11L);

        when(departments.findByName("ტექნიკური")).thenReturn(java.util.Optional.of(department));
        when(teams.findByDepartmentIdAndNameAndActiveTrue(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("ჯგუფი 01"), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable page = invocation.getArgument(2);
                    assertEquals(2, page.getPageSize(), "one sentinel row detects an ambiguous target");
                    return List.of(team);
                });

        assertEquals(java.util.Optional.of(11L),
                service.resolveUniqueActiveTeamId("ტექნიკური", "ჯგუფი 01"));

        when(teams.findByDepartmentIdAndNameAndActiveTrue(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("ჯგუფი 01"), any(Pageable.class)))
                .thenReturn(List.of(team, new Team()));
        assertTrue(service.resolveUniqueActiveTeamId("ტექნიკური", "ჯგუფი 01").isEmpty(),
                "duplicate canonical identities must fail closed before V37");
    }

    @Test
    void legacyPathRejectsBlankUnknownAndInactiveTargetsWithoutTeamLookup() {
        assertTrue(service.resolveUniqueActiveTeamId("", "ჯგუფი 01").isEmpty());
        verifyNoInteractions(departments, teams);

        when(departments.findByName("უცნობი")).thenReturn(java.util.Optional.empty());
        assertTrue(service.resolveUniqueActiveTeamId("უცნობი", "ჯგუფი 01").isEmpty());
        verifyNoInteractions(teams);

        Department inactive = new Department();
        inactive.setId(9L);
        inactive.setActive(false);
        when(departments.findByName("არააქტიური")).thenReturn(java.util.Optional.of(inactive));
        assertTrue(service.resolveUniqueActiveTeamId("არააქტიური", "ჯგუფი 01").isEmpty());
        verifyNoInteractions(teams);
    }
}
