package ge.magti.portal.web;

import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.content.ContentLifecycleService.ItemType;
import ge.magti.portal.content.ContentLifecycleService.Status;
import ge.magti.portal.content.LegalHoldAuthority;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.PermissionChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shared admin UI/API for recoverable content trash. */
@RestController
public class ContentTrashController {

    private final ContentLifecycleService lifecycleService;
    private final PermissionChecker permissionChecker;
    private final LegalHoldAuthority legalHoldAuthority;

    public ContentTrashController(
            ContentLifecycleService lifecycleService,
            PermissionChecker permissionChecker,
            LegalHoldAuthority legalHoldAuthority) {
        this.lifecycleService = lifecycleService;
        this.permissionChecker = permissionChecker;
        this.legalHoldAuthority = legalHoldAuthority;
    }

    @GetMapping("/api/content-trash")
    public ResponseEntity<?> listTrash(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(lifecycleService.listTrash());
    }

    @PostMapping("/api/content-trash/{itemType}/{itemId}/restore")
    public ResponseEntity<?> restore(
            @PathVariable String itemType, @PathVariable Long itemId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentManage(user);
        if (denial != null) {
            return denial;
        }
        ItemType type = ItemType.fromWireName(itemType);
        if (type == null) {
            return error(HttpStatus.BAD_REQUEST, "კონტენტის ტიპი არასწორია");
        }
        return response(lifecycleService.restore(type, itemId, user), "მასალა აღდგენილია");
    }

    @DeleteMapping("/api/content-trash/{itemType}/{itemId}")
    public ResponseEntity<?> purge(
            @PathVariable String itemType, @PathVariable Long itemId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        ItemType type = ItemType.fromWireName(itemType);
        if (type == null) {
            return error(HttpStatus.BAD_REQUEST, "კონტენტის ტიპი არასწორია");
        }
        return response(lifecycleService.purge(type, itemId, user), "მასალა საბოლოოდ წაიშალა");
    }

    @PostMapping("/api/content-trash/{itemType}/{itemId}/legal-hold")
    public ResponseEntity<?> setLegalHold(
            @PathVariable String itemType, @PathVariable Long itemId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireLegalHoldAuthority(user);
        if (denial != null) {
            return denial;
        }
        return changeLegalHold(itemType, itemId, true, user);
    }

    @DeleteMapping("/api/content-trash/{itemType}/{itemId}/legal-hold")
    public ResponseEntity<?> releaseLegalHold(
            @PathVariable String itemType, @PathVariable Long itemId,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireLegalHoldAuthority(user);
        if (denial != null) {
            return denial;
        }
        return changeLegalHold(itemType, itemId, false, user);
    }

    private ResponseEntity<?> changeLegalHold(
            String itemType, Long itemId, boolean hold, User user) {
        ItemType type = ItemType.fromWireName(itemType);
        if (type == null) {
            return error(HttpStatus.BAD_REQUEST, "კონტენტის ტიპი არასწორია");
        }
        return response(lifecycleService.changeLegalHold(type, itemId, hold, user),
                hold ? "legal hold ჩართულია" : "legal hold მოხსნილია");
    }

    static ResponseEntity<?> response(Status status, String successMessage) {
        return switch (status) {
            case OK -> ResponseEntity.ok(Map.of("detail", successMessage));
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "სანაგვეში მასალა ვერ მოიძებნა");
            case NOT_ARCHIVED -> error(HttpStatus.CONFLICT, "მასალა ჯერ უნდა დაარქივდეს");
            case RECOVERY_EXPIRED -> error(HttpStatus.GONE, "აღდგენის 30-დღიანი ვადა გასულია");
            case PURGE_NOT_DUE -> error(HttpStatus.CONFLICT, "საბოლოო წაშლის 30-დღიანი ვადა ჯერ არ გასულა");
            case LEGAL_HOLD -> error(HttpStatus.LOCKED, "მასალაზე მოქმედებს legal hold და მისი ცვლილება აკრძალულია");
            case NOT_AUTHORIZED -> error(HttpStatus.FORBIDDEN, "legal hold მართვის უფლება არ გაქვთ");
        };
    }

    private ResponseEntity<Map<String, String>> requireLegalHoldAuthority(User user) {
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "Could not validate credentials");
        }
        if (!legalHoldAuthority.canManage(user)) {
            return error(HttpStatus.FORBIDDEN, "legal hold მართვის უფლება არ გაქვთ");
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireContentManage(User user) {
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "Could not validate credentials");
        }
        if (!permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
            return error(HttpStatus.FORBIDDEN, "წვდომა უარყოფილია: არასაკმარისი უფლებები");
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "Could not validate credentials");
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return error(HttpStatus.FORBIDDEN, "წვდომა უარყოფილია: მხოლოდ სისტემური ადმინისტრატორისთვის");
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
