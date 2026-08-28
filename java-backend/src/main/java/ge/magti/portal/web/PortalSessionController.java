package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.ClientIpResolver;
import ge.magti.portal.security.JwtAuthenticationFilter;
import ge.magti.portal.security.PortalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PortalSessionController {
    private final PortalSessionService sessionService;
    private final MutationAuditService mutationAuditService;
    private final ClientIpResolver clientIpResolver;

    public PortalSessionController(
            PortalSessionService sessionService,
            MutationAuditService mutationAuditService,
            ClientIpResolver clientIpResolver) {
        this.sessionService = sessionService;
        this.mutationAuditService = mutationAuditService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/api/auth/session/heartbeat")
    public ResponseEntity<?> heartbeat(@AuthenticationPrincipal User user) {
        return user == null ? unauthorized() : ResponseEntity.noContent().build();
    }

    @GetMapping("/api/auth/sessions")
    public ResponseEntity<?> list(@AuthenticationPrincipal User user, HttpServletRequest request) {
        if (user == null) return unauthorized();
        String current = (String) request.getAttribute(JwtAuthenticationFilter.SESSION_REQUEST_ATTRIBUTE);
        return ResponseEntity.ok(sessionService.list(user.getId()).stream()
                .map(session -> PortalSessionResponse.from(session, current)).toList());
    }

    @DeleteMapping("/api/auth/sessions/{sessionId}")
    @Transactional
    public ResponseEntity<?> revoke(
            @PathVariable String sessionId,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        if (user == null) return unauthorized();
        if (sessionService.revoke(sessionId, user.getId())) {
            mutationAuditService.recordResult(
                    user,
                    "REVOKE_SESSION",
                    "user",
                    user.getId(),
                    user.getName(),
                    "SUCCESS",
                    null,
                    Map.of("session_active", true),
                    Map.of("session_active", false),
                    clientIpResolver.resolve(request),
                    truncatedUserAgent(request));
        }
        return ResponseEntity.noContent().build();
    }

    private static String truncatedUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return null;
        return userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "Could not validate credentials"));
    }
}
