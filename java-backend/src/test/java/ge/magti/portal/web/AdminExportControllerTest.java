package ge.magti.portal.web;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.AdminExportDataset;
import ge.magti.portal.export.AdminExportFamily;
import ge.magti.portal.export.AdminExportJobService;
import ge.magti.portal.export.AdminExportQueryService;
import ge.magti.portal.export.ExportJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminExportControllerTest {

    private final AdminExportQueryService queryService = mock(AdminExportQueryService.class);
    private final AdminExportJobService jobService = mock(AdminExportJobService.class);
    private final ExportJobWorker worker = mock(ExportJobWorker.class);
    private final AdminExportController controller = new AdminExportController(queryService, jobService, worker);

    @Test
    void unauthenticatedAndNonSystemAdminCallersAreRejectedBeforeDataAccess() {
        User manager = user(2L, Role.MANAGER);

        assertEquals(HttpStatus.UNAUTHORIZED, controller.auditLedger(null, null, null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.auditLedger(null, null, manager).getStatusCode());
        verify(queryService, never()).load(any(), any(), any());
    }

    @Test
    void invalidDateRangeIsRejectedBeforeDataAccess() {
        ResponseEntity<?> response = controller.searchHistory(
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1), user(1L, Role.SYSTEM_ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(queryService, never()).load(any(), any(), any());
    }

    @Test
    void everyFamilyCreatesItsOwnAuditedAsyncJob() {
        User admin = user(1L, Role.SYSTEM_ADMIN);
        for (AdminExportFamily family : AdminExportFamily.values()) {
            when(queryService.load(eq(family), any(), any()))
                    .thenReturn(new AdminExportDataset(family, List.of("სვეტი"), List.of(List.of("მონაცემი"))));
            when(jobService.register(eq(admin), eq(family), any(), any(), anyInt()))
                    .thenReturn("job-" + family.name());
        }

        List<ResponseEntity<?>> responses = List.of(
                controller.auditLedger(null, null, admin),
                controller.readEvidence(null, null, admin),
                controller.articleViews(null, null, admin),
                controller.searchHistory(null, null, admin),
                controller.quizAttempts(null, null, admin),
                controller.changeEvents(null, null, admin));

        responses.forEach(response -> assertEquals(HttpStatus.ACCEPTED, response.getStatusCode()));
        for (AdminExportFamily family : AdminExportFamily.values()) {
            verify(jobService).register(admin, family, null, null, 1);
            verify(worker).buildAdminAndStore(
                    "job-" + family.name(), family.title(), List.of("სვეტი"),
                    List.of(List.of("მონაცემი")), family.filenamePrefix());
        }
    }

    private static User user(long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setName("ტესტი");
        user.setEmail("test" + id + "@magti.ge");
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
