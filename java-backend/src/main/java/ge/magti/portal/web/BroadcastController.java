package ge.magti.portal.web;

import ge.magti.portal.announcement.BroadcastAuthorizationService;
import ge.magti.portal.announcement.BroadcastService;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final BroadcastAuthorizationService authorization;

    public BroadcastController(BroadcastService broadcastService, BroadcastAuthorizationService authorization) {
        this.broadcastService = broadcastService;
        this.authorization = authorization;
    }

    @GetMapping("/api/broadcasts")
    public ResponseEntity<?> getActive(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) return denial;
        OffsetDateTime now = TbilisiTime.now();
        List<BroadcastResponse> response = broadcastService.active().stream()
                .map(value -> BroadcastResponse.from(value, now, user)).toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/broadcasts")
    public ResponseEntity<?> publish(
            @Valid @RequestBody BroadcastRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAnnouncementPublisher(user);
        if (denial != null) return denial;
        try {
            BroadcastAnnouncement saved = broadcastService.publish(request, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(BroadcastResponse.from(saved, TbilisiTime.now(), user));
        } catch (BroadcastService.InvalidBroadcastException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/broadcasts/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAnnouncementPublisher(user);
        if (denial != null) return denial;
        if (page < 0 || size < 1 || size > 100) {
            return error(HttpStatus.BAD_REQUEST, "page უნდა იყოს 0 ან მეტი, size — 1-დან 100-მდე");
        }
        Page<BroadcastAnnouncement> result = broadcastService.history(page, size);
        OffsetDateTime now = TbilisiTime.now();
        return ResponseEntity.ok(new BroadcastHistoryResponse(
                result.getContent().stream().map(value -> BroadcastResponse.from(value, now, user)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping("/api/broadcasts/{broadcastId}/end")
    public ResponseEntity<?> endEarly(
            @PathVariable Long broadcastId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAnnouncementPublisher(user);
        if (denial != null) return denial;
        try {
            return ResponseEntity.ok(BroadcastResponse.from(
                    broadcastService.endEarly(broadcastId, user), TbilisiTime.now(), user));
        } catch (BroadcastService.BroadcastNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (BroadcastService.BroadcastOwnershipException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (BroadcastService.BroadcastStateException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> requireAnnouncementPublisher(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) return authFailure;
        return authorization.canPublish(user) ? null
                : error(HttpStatus.FORBIDDEN, "ამ მოქმედებისთვის საკმარისი უფლება არ გაქვთ");
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        return user == null ? error(HttpStatus.UNAUTHORIZED, "Could not validate credentials") : null;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
