package ge.magti.portal.web;

import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.User;
import ge.magti.portal.reminder.ReminderService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping("/api/reminders")
    public ResponseEntity<?> inbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) return denial;
        if (page < 0 || size < 1 || size > 100) {
            return error(HttpStatus.BAD_REQUEST, "page უნდა იყოს 0 ან მეტი, size — 1-დან 100-მდე");
        }
        Page<Reminder> result = reminderService.inbox(user, page, size);
        return ResponseEntity.ok(new ReminderPageResponse(
                result.getContent().stream().map(ReminderResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping("/api/reminders/{reminderId}/read")
    public ResponseEntity<?> markRead(
            @PathVariable long reminderId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) return denial;
        try {
            return ResponseEntity.ok(ReminderResponse.from(reminderService.markRead(reminderId, user)));
        } catch (ReminderService.ReminderNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** No request body by design: leaders cannot supply free text. */
    @PostMapping("/api/reminders/users/{userId}/send")
    public ResponseEntity<?> sendManual(
            @PathVariable long userId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) return denial;
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ReminderResponse.from(reminderService.sendManual(userId, user)));
        } catch (ReminderService.ReminderNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ReminderService.ReminderScopeException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (ReminderService.InvalidReminderTargetException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (ReminderService.ReminderCooldownException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("detail", e.getMessage());
            body.put("retry_at", e.retryAt());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
