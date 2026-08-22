package ge.magti.portal.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.BroadcastAnnouncementRepository;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.BroadcastRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transactional lifecycle and immutable audit snapshots for broadcasts. */
@Service
public class BroadcastService {

    private final BroadcastAnnouncementRepository repository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public BroadcastService(
            BroadcastAnnouncementRepository repository,
            AuditLogRepository auditLogRepository) {
        this.repository = repository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public List<BroadcastAnnouncement> active() {
        return repository.findByEndedAtIsNullAndEndsAtAfterOrderByPublishedAtDesc(TbilisiTime.now());
    }

    @Transactional(readOnly = true)
    public Page<BroadcastAnnouncement> history(int page, int size) {
        return repository.findAllByOrderByPublishedAtDesc(PageRequest.of(page, size));
    }

    @Transactional
    public BroadcastAnnouncement publish(BroadcastRequest request, User actor) {
        OffsetDateTime now = TbilisiTime.now();
        if (!request.endsAt().isAfter(now)) {
            throw new InvalidBroadcastException("დასრულების დრო მომავალში უნდა იყოს");
        }

        BroadcastAnnouncement announcement = new BroadcastAnnouncement();
        announcement.setMessage(request.message().trim());
        announcement.setPriority(request.priority());
        announcement.setPublishedAt(now);
        announcement.setEndsAt(request.endsAt());
        announcement.setPublishedByUserId(actor.getId());
        announcement.setPublisherNameSnapshot(actor.getName());
        BroadcastAnnouncement saved = repository.saveAndFlush(announcement);
        writeAudit(saved, actor, "PUBLISH_BROADCAST");
        return saved;
    }

    @Transactional
    public BroadcastAnnouncement endEarly(Long id, User actor) {
        BroadcastAnnouncement announcement = repository.findById(id)
                .orElseThrow(() -> new BroadcastNotFoundException("განცხადება ვერ მოიძებნა"));
        if (actor.getRole() != Role.SYSTEM_ADMIN && !actor.getId().equals(announcement.getPublishedByUserId())) {
            throw new BroadcastOwnershipException("განცხადების დროზე ადრე მოხსნა მხოლოდ მის გამომქვეყნებელს შეუძლია");
        }

        OffsetDateTime now = TbilisiTime.now();
        if (announcement.getEndedAt() != null || !announcement.getEndsAt().isAfter(now)) {
            throw new BroadcastStateException("განცხადება უკვე დასრულებულია");
        }
        announcement.setEndedAt(now);
        announcement.setEndedByUserId(actor.getId());
        announcement.setEndedByNameSnapshot(actor.getName());
        BroadcastAnnouncement saved = repository.saveAndFlush(announcement);
        writeAudit(saved, actor, "END_BROADCAST_EARLY");
        return saved;
    }

    private void writeAudit(BroadcastAnnouncement announcement, User actor, String action) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(actor.getId());
        audit.setAction(action);
        audit.setItemType("broadcast");
        audit.setItemId(announcement.getId());
        audit.setTimestamp(TbilisiTime.now());
        audit.setAdminNameSnapshot(actor.getName());
        audit.setAdminEmailSnapshot(actor.getEmail());
        audit.setItemNameSnapshot(announcement.getMessage().substring(0, Math.min(200, announcement.getMessage().length())));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("audience", "ALL_AUTHENTICATED");
        details.put("priority", announcement.getPriority().name());
        details.put("message", announcement.getMessage());
        details.put("published_at", announcement.getPublishedAt().toString());
        details.put("ends_at", announcement.getEndsAt().toString());
        if (announcement.getEndedAt() != null) {
            details.put("ended_at", announcement.getEndedAt().toString());
        }
        try {
            audit.setDetails(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Broadcast audit serialization failed", e);
        }
        auditLogRepository.save(audit);
    }

    public static class InvalidBroadcastException extends RuntimeException {
        public InvalidBroadcastException(String message) { super(message); }
    }
    public static class BroadcastNotFoundException extends RuntimeException {
        public BroadcastNotFoundException(String message) { super(message); }
    }
    public static class BroadcastOwnershipException extends RuntimeException {
        public BroadcastOwnershipException(String message) { super(message); }
    }
    public static class BroadcastStateException extends RuntimeException {
        public BroadcastStateException(String message) { super(message); }
    }
}
