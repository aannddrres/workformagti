package ge.magti.portal.web;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.AccessDiffService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** SYSTEM_ADMIN-only, read-only evidence for the Phase 4/5 cutover. */
@RestController
public class AccessDiffController {

    private final AccessDiffService accessDiffService;

    public AccessDiffController(AccessDiffService accessDiffService) {
        this.accessDiffService = accessDiffService;
    }

    @GetMapping("/api/admin/access-diff")
    public ResponseEntity<?> getAccessDiff(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(accessDiffService.report());
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
