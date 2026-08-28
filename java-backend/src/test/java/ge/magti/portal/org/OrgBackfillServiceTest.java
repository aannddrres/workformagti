package ge.magti.portal.org;

import ge.magti.portal.domain.User;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrgBackfillServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final LeadershipAssignmentRepository assignments = mock(LeadershipAssignmentRepository.class);
    private final UserDirectoryQueryService directory = mock(UserDirectoryQueryService.class);
    private final OrgDirectoryQueryService orgDirectory = mock(OrgDirectoryQueryService.class);
    private final OrgBackfillService service =
            new OrgBackfillService(users, teams, assignments, directory, orgDirectory);

    @Test
    void planFailsBeforeOrgQueriesWhenActiveDirectoryExceedsTheCeiling() {
        when(directory.listActiveUsersWithinLimit())
                .thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());

        assertThrows(UserDirectoryQueryService.ActiveUserCardinalityExceededException.class, service::plan);

        verifyNoInteractions(orgDirectory, teams, assignments, users);
    }

    @Test
    void applyUsesOneBoundedActiveUserSnapshotForPlanAndMutationPhases() {
        when(directory.listActiveUsersWithinLimit()).thenReturn(List.of());
        when(orgDirectory.listDepartmentsWithinLimit()).thenReturn(List.of());
        when(orgDirectory.listTeamsWithinLimit()).thenReturn(List.of());

        assertDoesNotThrow(() -> service.apply(7L));

        verify(directory).listActiveUsersWithinLimit();
        verify(users, never()).findByActiveTrue();
    }

    @Test
    void onlyThePreMutationCardinalityFailureMayCommitItsRejectionAudit() throws Exception {
        Transactional transactional = OrgBackfillService.class
                .getMethod("apply", Long.class)
                .getAnnotation(Transactional.class);

        assertArrayEquals(
                new Class<?>[] {
                        UserDirectoryQueryService.ActiveUserCardinalityExceededException.class,
                        OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class},
                transactional.noRollbackFor());
    }
}
