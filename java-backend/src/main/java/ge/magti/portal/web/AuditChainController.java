package ge.magti.portal.web;

import ge.magti.portal.audit.AuditChainService;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.PermissionChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mirrors routers/audit_logs.py's verify_audit_log (:324-376) and
 * audit_chain_health (:379-455) endpoints and their access rule --
 * {@code security.require_permission(PERM_SYSTEM_AUDIT,
 * exclude_roles={ROLE_MANAGER})}: a manager holds system.audit for the
 * scoped list view but not this tool (one global chain, no per-department
 * slice to meaningfully verify).
 *
 * <p>The three distinct failure modes and their exact detail text are
 * ported as-is from security.py's get_current_user/require_permission:
 * no valid token (401, English message, matching the source exactly),
 * missing permission (403, Georgian), and excluded role despite having
 * the permission (403, a different Georgian message) -- in that order,
 * since Python checks them in that order too.
 */
@RestController
public class AuditChainController {

    private final AuditChainService auditChainService;
    private final PermissionChecker permissionChecker;

    public AuditChainController(AuditChainService auditChainService, PermissionChecker permissionChecker) {
        this.auditChainService = auditChainService;
        this.permissionChecker = permissionChecker;
    }

    @GetMapping("/api/audit-logs/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = authorize(user);
        if (denial != null) {
            return denial;
        }
        return auditChainService.verify(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "ჩანაწერი ვერ მოიძებნა")));
    }

    @GetMapping("/api/audit-logs/chain-health")
    public ResponseEntity<?> chainHealth(
            @RequestParam(defaultValue = "100") int n, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = authorize(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(auditChainService.chainHealth(n));
    }

    private ResponseEntity<Map<String, String>> authorize(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (!permissionChecker.hasPermission(user, Permission.SYSTEM_AUDIT)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        if (user.getRole() == Role.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "ეს ფუნქცია ხელმისაწვდომია მხოლოდ ადმინისტრატორებისთვის"));
        }
        return null;
    }
}
