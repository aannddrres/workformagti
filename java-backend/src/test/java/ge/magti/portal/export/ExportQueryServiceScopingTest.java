package ge.magti.portal.export;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free proof of the SEC-02 department-scoping rule, so it gates every PR
 * rather than only the runs that happen to have a live Oracle 19c --
 * {@link ge.magti.portal.web.ExportControllerIntegrationTest} covers the
 * same rule end to end through the real filter chain, but errors out with
 * ORA-12541 wherever the DB is absent.
 *
 * <p>The repositories are stubbed with a small fixed org: two operators in
 * the manager's own department, one in another. The
 * {@code computeCompliance(ids, null)} stub genuinely filters by the id list
 * it is handed rather than returning a canned list, so these tests fail if
 * {@link ExportQueryService} passes the wrong ids -- not just if it forgets
 * to branch at all.
 */
class ExportQueryServiceScopingTest {

    private static final String OWN_DEPT = "ტექნიკური — ჯგუფი 03";
    private static final String OTHER_DEPT = "ოფისი — ჯგუფი 01";

    private final ComplianceQueryService complianceQueryService = mock(ComplianceQueryService.class);
    private final ReadStatusRepository readStatusRepository = mock(ReadStatusRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RequiredReadingRepository requiredReadingRepository = mock(RequiredReadingRepository.class);

    private final ExportQueryService service = new ExportQueryService(
            complianceQueryService, readStatusRepository, userRepository, requiredReadingRepository);

    private final User ownOperatorA = operator(1L, "ოპერატორი A", OWN_DEPT);
    private final User ownOperatorB = operator(2L, "ოპერატორი B", OWN_DEPT);
    private final User otherOperator = operator(3L, "სხვისი ოპერატორი", OTHER_DEPT);
    private final List<User> allOperators = List.of(ownOperatorA, ownOperatorB, otherOperator);

    ExportQueryServiceScopingTest() {
        List<ComplianceRecord> orgWide = allOperators.stream().map(ExportQueryServiceScopingTest::record).toList();
        when(complianceQueryService.computeCompliance()).thenReturn(orgWide);
        when(complianceQueryService.computeCompliance(anyList(), isNull())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return orgWide.stream().filter(r -> ids.contains(r.user().getId())).toList();
        });
        // Scoping is applied in Java by ManagerScope, not by a per-department
        // query, since the rule normalises the string before comparing
        // (SEC-13). The repository hands over every active user.
        when(userRepository.findByActiveTrue()).thenReturn(allOperators);

        // One read status per operator, all against the same required reading.
        when(readStatusRepository.findByUserIdIn(anyList(), any(Pageable.class))).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(ExportQueryServiceScopingTest::readStatus).toList();
        });
        when(userRepository.findAllById(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return allOperators.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(requiredReadingRepository.findAllById(anyList())).thenReturn(List.of(requiredReading()));
    }

    @Test
    void managerReadingRowsCarryOnlyTheirOwnDepartment() {
        List<ReadingExportRow> rows = service.eligibleReadingRows(managerOf(OWN_DEPT));

        assertEquals(List.of(1L, 2L), rows.stream().map(ReadingExportRow::userId).sorted().toList());
        assertTrue(rows.stream().allMatch(r -> OWN_DEPT.equals(r.department())));
        assertFalse(rows.stream().anyMatch(r -> otherOperator.getName().equals(r.userName())),
                "SEC-02: another department's operator must not appear in a manager's export");
    }

    @Test
    void onlySystemAdminReadingRowsStayOrgWide() {
        List<ReadingExportRow> rows = service.eligibleReadingRows(userOf(Role.SYSTEM_ADMIN, "All"));

        assertEquals(List.of(1L, 2L, 3L), rows.stream().map(ReadingExportRow::userId).sorted().toList());
    }

    /**
     * The inner of two layers, and not the product rule on its own.
     *
     * <p>{@code ExportController.requireReportsExport} refuses these callers
     * outright ({@code ExportControllerScopeGateTest}), because holding
     * {@code reports.export} must not produce a readable set of colleagues.
     * What this pins is the fallback: if a future call path reaches the
     * service without that gate, it must degrade to one department rather
     * than to the whole company.
     */
    @Test
    void aNonAdminReachingTheServiceDirectlyStillCannotReadOrgWide() {
        for (Role role : List.of(Role.CONTENT_ADMIN, Role.OPERATOR)) {
            List<ReadingExportRow> rows = service.eligibleReadingRows(userOf(role, OWN_DEPT));

            assertEquals(List.of(1L, 2L), rows.stream().map(ReadingExportRow::userId).sorted().toList(),
                    role + " must never fall through to the unscoped branch");
        }
    }

    @Test
    void managerDepartmentTotalsCoverOnlyTheirOwnDepartment() {
        SortedMap<String, int[]> totals = service.departmentComplianceTotals(managerOf(OWN_DEPT));

        assertEquals(List.of(OWN_DEPT), List.copyOf(totals.keySet()));
        assertEquals(8, totals.get(OWN_DEPT)[0], "two operators' required counts");
        assertEquals(4, totals.get(OWN_DEPT)[1], "two operators' read counts");
    }

    @Test
    void systemAdminDepartmentTotalsStayOrgWide() {
        SortedMap<String, int[]> totals = service.departmentComplianceTotals(userOf(Role.SYSTEM_ADMIN, "All"));

        assertEquals(List.of(OTHER_DEPT, OWN_DEPT), List.copyOf(totals.keySet()).stream().sorted().toList());
    }

    @Test
    void nonAdminWildcardOrMissingCallerFailsClosed() {
        assertTrue(service.eligibleReadingRows(userOf(Role.CONTENT_ADMIN, "All")).isEmpty());
        assertTrue(service.departmentComplianceTotals(userOf(Role.OPERATOR, null)).isEmpty());
        assertTrue(service.eligibleReadingRows(null).isEmpty());
    }

    /**
     * Fail closed, not open: a manager row with no department must export
     * nothing rather than fall back to the org-wide branch. This is the shape
     * the bug took -- a missing scope silently meaning "everything".
     */
    @Test
    void managerWithNoDepartmentGetsAnEmptyExportRatherThanEverything() {
        User manager = managerOf(null);

        assertTrue(service.eligibleReadingRows(manager).isEmpty());
        assertTrue(service.departmentComplianceTotals(manager).isEmpty());
    }

    /**
     * SEC-13. The comment on {@code scopedCompliance} used to say this
     * manager exporting an empty file was a deliberately-unchanged
     * limitation. It exports their subtree now, and — the half that matters
     * — still not the neighbouring department.
     */
    @Test
    void parentDepartmentManagerExportsTheirSubtreeNotAnEmptyFile() {
        List<ReadingExportRow> rows = service.eligibleReadingRows(managerOf("ტექნიკური"));

        assertEquals(List.of(1L, 2L), rows.stream().map(ReadingExportRow::userId).sorted().toList());
        assertFalse(rows.stream().anyMatch(r -> otherOperator.getName().equals(r.userName())));
    }

    @Test
    void scopeDepartmentForReportsTheEffectiveScopeUsedByTheExportAuditRow() {
        assertTrue(ExportQueryService.isDepartmentScoped(managerOf(OWN_DEPT)));
        assertEquals(OWN_DEPT, ExportQueryService.scopeDepartmentFor(managerOf(OWN_DEPT)));

        assertFalse(ExportQueryService.isDepartmentScoped(userOf(Role.SYSTEM_ADMIN, "All")));
        assertNull(ExportQueryService.scopeDepartmentFor(userOf(Role.SYSTEM_ADMIN, "All")));
        assertTrue(ExportQueryService.isDepartmentScoped(userOf(Role.CONTENT_ADMIN, OWN_DEPT)));
        assertEquals(OWN_DEPT, ExportQueryService.scopeDepartmentFor(userOf(Role.CONTENT_ADMIN, OWN_DEPT)));
        assertFalse(ExportQueryService.isDepartmentScoped(null));

        // A manager with no department is still SCOPED -- its null scope must
        // not read as "unrestricted", which is what broke the fail-closed case.
        assertTrue(ExportQueryService.isDepartmentScoped(managerOf(null)));
        assertNull(ExportQueryService.scopeDepartmentFor(managerOf(null)));
    }

    @Test
    void productionConstructorUsesTheBoundedActiveDirectorySnapshot() {
        UserDirectoryQueryService directory = mock(UserDirectoryQueryService.class);
        when(directory.listActiveUsersWithinLimit()).thenReturn(allOperators);
        ExportQueryService bounded = new ExportQueryService(
                complianceQueryService, readStatusRepository, userRepository,
                requiredReadingRepository, null, directory);

        List<ReadingExportRow> rows = bounded.eligibleReadingRows(managerOf(OWN_DEPT));

        assertEquals(List.of(1L, 2L), rows.stream().map(ReadingExportRow::userId).sorted().toList());
        verify(directory).listActiveUsersWithinLimit();
        verify(userRepository, never()).findByActiveTrue();
    }

    @Test
    void oversizedReadingExportStopsAtDatabaseSentinelBeforeReferenceHydration() {
        when(readStatusRepository.findByUserIdIn(anyList(), any(Pageable.class)))
                .thenReturn(Collections.nCopies(
                        ExportSizeGuard.MAX_ROWS + 1,
                        readStatus(ownOperatorA.getId())));

        assertThrows(ExportTooLargeException.class,
                () -> service.eligibleReadingRows(userOf(Role.SYSTEM_ADMIN, "All")));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(readStatusRepository).findByUserIdIn(anyList(), page.capture());
        assertEquals(ExportSizeGuard.MAX_ROWS + 1, page.getValue().getPageSize());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals("id: ASC", page.getValue().getSort().toString());
        verify(userRepository, never()).findAllById(anyList());
        verify(requiredReadingRepository, never()).findAllById(anyList());
    }

    private static User operator(Long id, String name, String department) {
        User user = userOf(Role.OPERATOR, department);
        user.setId(id);
        user.setName(name);
        return user;
    }

    private static User managerOf(String department) {
        return userOf(Role.MANAGER, department);
    }

    private static User userOf(Role role, String department) {
        User user = new User();
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        return user;
    }

    private static ComplianceRecord record(User user) {
        return new ComplianceRecord(user, new ReadingProgress(4, 2, 50));
    }

    private static ReadStatus readStatus(Long userId) {
        ReadStatus status = new ReadStatus();
        status.setUserId(userId);
        status.setRequiredReadingId(100L);
        status.setStatus("read");
        return status;
    }

    private static RequiredReading requiredReading() {
        RequiredReading reading = new RequiredReading();
        reading.setId(100L);
        reading.setItemType("article");
        reading.setItemId(42L);
        return reading;
    }
}
