package ge.magti.portal.web;

import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Message;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.messaging.DirectMessagePermission;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.MessageRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors routers/messaging.py's 6 durable, non-real-time endpoints: the
 * operator inbox/manager-sent-messages CRUD. Broadcasts moved to the
 * independent {@link BroadcastController} domain and never create Message rows.
 *
 * <p><b>Deliberately not ported: {@code GET /api/stream}</b> (the SSE
 * live-event connection, routers/messaging.py:71-147). Presented to the user
 * concretely before starting this domain: the feature underneath it is a
 * one-directional manager/admin-to-operator compliance-nudge tool (triggered
 * from a plain {@code prompt()} dialog on the team-stats dashboard, not a
 * chat UI) plus an admin broadcast -- not a messaging product that needs
 * live push to be useful. User agreed to defer it, same durable-only
 * degradation already used for every other SSE call site in this port
 * (see {@link ge.magti.portal.compliance.RequiredReadingNotifier}). A
 * recipient sees a new message/broadcast on their next page load (inbox
 * fetch), not instantly. {@link ge.magti.portal.messaging.SseEventVisibility}
 * (the DB-free delivery filter the stream would use) already exists,
 * unused, ready for whenever this is revisited.
 *
 * <p>{@code sender_name}/{@code recipient_name} (models.py's relationship
 * {@code @property}s) are resolved here via a small batch lookup rather than
 * per-row, avoiding an N+1 query for a list endpoint.
 *
 * <p>This controller remains temporarily only for the legacy direct-message
 * removal release. No new feature may depend on it.
 */
@RestController
public class MessagingController {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public MessagingController(
            MessageRepository messageRepository, UserRepository userRepository, AuditLogRepository auditLogRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** Port of get_my_messages (routers/messaging.py:150-168). */
    @GetMapping("/api/messages")
    public ResponseEntity<?> getMyMessages(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(toResponses(messageRepository.findByUserIdOrderByCreatedAtDesc(user.getId())));
    }

    /** Port of get_sent_messages (routers/messaging.py:171-182). */
    @GetMapping("/api/messages/sent")
    public ResponseEntity<?> getSentMessages(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(toResponses(messageRepository.findBySenderIdOrderByCreatedAtDesc(user.getId())));
    }

    /** Port of send_message (routers/messaging.py:185-237). */
    @PostMapping("/api/messages")
    @Transactional
    public ResponseEntity<?> sendMessage(@Valid @RequestBody MessageRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireManagerOrAdmin(user);
        if (denial != null) {
            return denial;
        }
        Optional<User> recipientOpt = userRepository.findById(request.userId());
        if (recipientOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მიმღები ვერ მოიძებნა"));
        }
        User recipient = recipientOpt.get();

        if (!DirectMessagePermission.canSend(user.getRole(), user.getDepartment(), recipient.getDepartment())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "detail", "მენეჯერებს შეუძლიათ შეტყობინების გაგზავნა მხოლოდ საკუთარი დეპარტამენტის თანამშრომლებისთვის"));
        }

        Message message = new Message();
        message.setUserId(recipient.getId());
        message.setSenderId(user.getId());
        message.setContent(request.content());
        message.setCreatedAt(TbilisiTime.now());
        Message saved = messageRepository.saveAndFlush(message);

        AuditLog audit = new AuditLog();
        audit.setAdminId(user.getId());
        audit.setAction("SEND_MESSAGE");
        audit.setItemType("user");
        audit.setItemId(recipient.getId());
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        return ResponseEntity.ok(toResponses(List.of(saved)).get(0));
    }

    /** Port of mark_message_read (routers/messaging.py:240-271). */
    @PostMapping("/api/messages/{messageId}/read")
    public ResponseEntity<?> markMessageRead(@PathVariable("messageId") Long messageId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<Message> found = messageRepository.findByIdAndUserId(messageId, user.getId());
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "შეტყობინება ვერ მოიძებნა"));
        }
        Message message = found.get();
        message.setRead(true);
        Message saved = messageRepository.save(message);
        return ResponseEntity.ok(toResponses(List.of(saved)).get(0));
    }

    /** Port of delete_message (routers/messaging.py:274-304). */
    @DeleteMapping("/api/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable("messageId") Long messageId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<Message> found = messageRepository.findByIdAndUserId(messageId, user.getId());
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "შეტყობინება ვერ მოიძებნა"));
        }
        messageRepository.delete(found.get());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private List<MessageResponse> toResponses(List<Message> messages) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m.getSenderId() != null) {
                userIds.add(m.getSenderId());
            }
            userIds.add(m.getUserId());
        }
        Map<Long, String> namesById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
        return messages.stream()
                .map(m -> MessageResponse.from(m, namesById.get(m.getSenderId()), namesById.get(m.getUserId())))
                .toList();
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireManagerOrAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.MANAGER && user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }

}
