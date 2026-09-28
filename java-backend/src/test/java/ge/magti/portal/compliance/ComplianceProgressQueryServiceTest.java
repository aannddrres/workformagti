package ge.magti.portal.compliance;

import ge.magti.portal.compliance.ComplianceAggregateRepository.AggregateRow;
import ge.magti.portal.compliance.ComplianceAggregateRepository.ScopeTarget;
import ge.magti.portal.domain.User;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplianceProgressQueryServiceTest {

    private final ComplianceAggregateRepository repository = mock(ComplianceAggregateRepository.class);
    private final MandatoryReach reach = mock(MandatoryReach.class);
    private final ComplianceProgressQueryService service = new ComplianceProgressQueryService(repository, reach);
    private static final Set<Long> IN_FORCE = Set.of(11L, 12L);

    @Test
    void derivesAtMostThreeExactTargetsPerUserWithoutReimplementingDepartmentParsingInSql() {
        User all = user(1L, "All");
        User plain = user(2L, "ოფისი");
        User grouped = user(3L, "ტექნიკური — ჯგუფი 03");

        assertEquals(List.of(
                new ScopeTarget(1L, "All"),
                new ScopeTarget(2L, "All"),
                new ScopeTarget(2L, "ოფისი"),
                new ScopeTarget(3L, "All"),
                new ScopeTarget(3L, "ტექნიკური — ჯგუფი 03"),
                new ScopeTarget(3L, "ტექნიკური")),
                ComplianceProgressQueryService.scopeTargetsFor(List.of(all, plain, grouped)));
    }

    @Test
    void mapsBoundedAggregateRowsThroughTheExistingComplianceFormula() {
        User grouped = user(7L, "ტექნიკური — ჯგუფი 03");
        List<ScopeTarget> scopes = ComplianceProgressQueryService.scopeTargetsFor(List.of(grouped));
        when(reach.inForceIdsForTargets(any())).thenReturn(IN_FORCE);
        when(repository.findRelevantCounts(scopes, IN_FORCE)).thenReturn(List.of(
                new AggregateRow(7L, "All", 2, 2),
                new AggregateRow(7L, "ტექნიკური — ჯგუფი 03", 1, 0),
                new AggregateRow(7L, "ტექნიკური", 1, 1)));

        Map<Long, ReadingProgress> result = service.progressByUser(List.of(grouped));

        assertEquals(new ReadingProgress(4, 3, 75), result.get(7L));
        verify(repository).findRelevantCounts(scopes, IN_FORCE);
    }

    /**
     * PO-40: Oracle counts only the readings MandatoryReach says are in force,
     * asked for exactly the target strings the user's scope produced.
     */
    @Test
    void countsOnlyTheReadingsInForceForTheScopesOwnTargets() {
        User grouped = user(5L, "ტექნიკური — ჯგუფი 03");
        List<ScopeTarget> scopes = ComplianceProgressQueryService.scopeTargetsFor(List.of(grouped));
        when(reach.inForceIdsForTargets(any())).thenReturn(IN_FORCE);
        when(repository.findRelevantCounts(scopes, IN_FORCE)).thenReturn(List.of(
                new AggregateRow(5L, "All", 0, 0),
                new AggregateRow(5L, "ტექნიკური — ჯგუფი 03", 0, 0),
                new AggregateRow(5L, "ტექნიკური", 0, 0)));

        service.progressByUser(List.of(grouped));

        verify(reach).inForceIdsForTargets(List.of("All", "ტექნიკური — ჯგუფი 03", "ტექნიკური"));
        verify(repository).findRelevantCounts(scopes, IN_FORCE);
    }

    @Test
    void failsClosedWhenOracleDoesNotReturnExactlyOneRowPerScopeTarget() {
        User user = user(8L, "All");
        when(reach.inForceIdsForTargets(any())).thenReturn(IN_FORCE);
        when(repository.findRelevantCounts(anyList(), any())).thenReturn(List.of());

        assertThrows(ComplianceProgressQueryService.ComplianceAggregateShapeException.class,
                () -> service.progressByUser(List.of(user)));
    }

    @Test
    void independentlyEnforcesTheExistingOneThousandUserContract() {
        User user = user(9L, "All");

        assertThrows(UserDirectoryQueryService.ActiveUserCardinalityExceededException.class,
                () -> service.progressByUser(Collections.nCopies(
                        UserDirectoryQueryService.MAX_ACTIVE_USERS + 1, user)));
    }

    private static User user(Long id, String department) {
        User user = new User();
        user.setId(id);
        user.setDepartment(department);
        return user;
    }
}
