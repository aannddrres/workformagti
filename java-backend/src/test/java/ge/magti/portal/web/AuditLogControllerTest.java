package ge.magti.portal.web;

import ge.magti.portal.audit.AuditChainService;
import ge.magti.portal.audit.AuditLogQueryService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.PermissionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast, DB-free proof of the authorization branching mirrored from
 * security.py's get_current_user/require_permission: the three failure
 * modes (no token and non-system-admin role) plus the pass-through case.
 * The slower, real-Oracle wiring proof (does
 * {@code @AuthenticationPrincipal} really resolve through the actual
 * filter chain) lives in AuditLogControllerIntegrationTest.
 *
 * Raw audit is a role-only SYSTEM_ADMIN boundary rather than a grantable
 * capability.
 */
class AuditLogControllerTest {

    private final PermissionChecker permissionChecker = new PermissionChecker();
    private final AuditChainService auditChainService = mock(AuditChainService.class);
    private final AuditLogQueryService auditLogQueryService = mock(AuditLogQueryService.class);
    private final MutationAuditService mutationAuditService = mock(MutationAuditService.class);
    private final AuditLogController controller = new AuditLogController(
            auditChainService, auditLogQueryService, mutationAuditService, permissionChecker);

    private static User userWith(Role role, Set<String> permissions) {
        User user = new User();
        user.setRole(role);
        user.setPermissions(permissions);
        return user;
    }

    @Test
    void listDefaultsToNewestFirstAndOnlyFlipsOnAnExplicitAsc() {
        User admin = userWith(Role.SYSTEM_ADMIN, Set.of());
        when(auditLogQueryService.list(any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new AuditLogQueryService.Page(List.of(), 0L));

        controller.list(null, null, null, null, null, null, null, 50, 0, "desc", admin);
        verify(auditLogQueryService).list(any(), any(), anyInt(), anyInt(), eq(false));

        controller.list(null, null, null, null, null, null, null, 50, 0, "asc", admin);
        verify(auditLogQueryService).list(any(), any(), anyInt(), anyInt(), eq(true));
    }

    @Test
    void listTreatsAnythingThatIsNotAscAsNewestFirst() {
        User admin = userWith(Role.SYSTEM_ADMIN, Set.of());
        when(auditLogQueryService.list(any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new AuditLogQueryService.Page(List.of(), 0L));

        // The direction never reaches SQL as text, so a hostile value is simply
        // not "asc" and the default order stands.
        controller.list(null, null, null, null, null, null, null, 50, 0, "timestamp; DROP TABLE audit_logs", admin);

        verify(auditLogQueryService).list(any(), any(), anyInt(), anyInt(), eq(false));
    }

    @Test
    void noAuthenticatedUserIsUnauthorized() {
        ResponseEntity<?> response = controller.verify(1L, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(Map.of("detail", "Could not validate credentials"), response.getBody());
    }

    @Test
    void operatorIsForbiddenByTheSystemAdminOnlyBoundary() {
        User operator = userWith(Role.OPERATOR, Set.of());

        ResponseEntity<?> response = controller.verify(1L, operator);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("detail", "ეს ფუნქცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის"), response.getBody());
    }

    @Test
    void managerIsForbiddenFromRawAudit() {
        User manager = userWith(Role.MANAGER, Set.of());

        ResponseEntity<?> response = controller.chainHealth(100, manager);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("detail", "ეს ფუნქცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის"), response.getBody());
    }

    @Test
    void systemAdminBypassesEverythingAndReachesTheService() {
        User admin = userWith(Role.SYSTEM_ADMIN, Set.of());
        when(auditChainService.verify(42L)).thenReturn(Optional.of(
                new AuditVerifyResponse("ok", true, true, "abc", "abc")));

        ResponseEntity<?> response = controller.verify(42L, admin);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", ((AuditVerifyResponse) response.getBody()).status());
    }

    @Test
    void contentAdminIsForbiddenFromRawAudit() {
        User contentAdmin = userWith(Role.CONTENT_ADMIN, Set.of());

        ResponseEntity<?> response = controller.chainHealth(100, contentAdmin);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void verifyReturns404WithGeorgianDetailWhenTheServiceFindsNoRow() {
        User admin = userWith(Role.SYSTEM_ADMIN, Set.of());
        when(auditChainService.verify(999L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verify(999L, admin);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(Map.of("detail", "ჩანაწერი ვერ მოიძებნა"), response.getBody());
    }
}
