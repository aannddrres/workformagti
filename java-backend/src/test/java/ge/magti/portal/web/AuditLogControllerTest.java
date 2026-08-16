package ge.magti.portal.web;

import ge.magti.portal.audit.AuditChainService;
import ge.magti.portal.audit.AuditLogQueryService;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.PermissionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fast, DB-free proof of the authorization branching mirrored from
 * security.py's get_current_user/require_permission: the three failure
 * modes (no token, missing permission, permission-but-excluded-role) in
 * that exact order, each with its exact source detail text, plus the
 * pass-through case. The slower, real-Oracle wiring proof (does
 * {@code @AuthenticationPrincipal} really resolve through the actual
 * filter chain) lives in AuditLogControllerIntegrationTest.
 *
 * <p>Only exercises verify/chainHealth's requireSystemAuditNonManager
 * branching -- list/export's plain requireSystemAudit (no manager
 * exclusion) is covered by the integration test instead, since it needs a
 * real scoped-department query to be meaningful.
 */
class AuditLogControllerTest {

    private final PermissionChecker permissionChecker = new PermissionChecker();
    private final AuditChainService auditChainService = mock(AuditChainService.class);
    private final AuditLogQueryService auditLogQueryService = mock(AuditLogQueryService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogController controller = new AuditLogController(
            auditChainService, auditLogQueryService, auditLogRepository, permissionChecker, userRepository);

    private static User userWith(Role role, Set<String> permissions) {
        User user = new User();
        user.setRole(role);
        user.setPermissions(permissions);
        return user;
    }

    @Test
    void noAuthenticatedUserIsUnauthorized() {
        ResponseEntity<?> response = controller.verify(1L, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(Map.of("detail", "Could not validate credentials"), response.getBody());
    }

    @Test
    void userWithoutThePermissionIsForbiddenWithTheInsufficientPermissionsMessage() {
        User operator = userWith(Role.OPERATOR, Set.of());

        ResponseEntity<?> response = controller.verify(1L, operator);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"), response.getBody());
    }

    @Test
    void managerWithThePermissionIsStillForbiddenWithTheAdminsOnlyMessage() {
        // A manager who somehow holds system.audit (e.g. the scoped list
        // view's grant) must still be denied THIS tool specifically --
        // the exact carve-out security.py's exclude_roles documents.
        User manager = userWith(Role.MANAGER, Set.of(Permission.SYSTEM_AUDIT.value()));

        ResponseEntity<?> response = controller.chainHealth(100, manager);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("detail", "ეს ფუნქცია ხელმისაწვდომია მხოლოდ ადმინისტრატორებისთვის"), response.getBody());
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
    void contentAdminWithThePermissionReachesTheService() {
        User contentAdmin = userWith(Role.CONTENT_ADMIN, Set.of(Permission.SYSTEM_AUDIT.value()));
        when(auditChainService.chainHealth(any(Integer.class))).thenReturn(
                new AuditChainHealthResponse("ok", 5, 100, 0, 0, java.util.List.of(), 0));

        ResponseEntity<?> response = controller.chainHealth(100, contentAdmin);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", ((AuditChainHealthResponse) response.getBody()).status());
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
