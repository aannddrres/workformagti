package ge.magti.portal.announcement;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.BroadcastAnnouncementRepository;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.BroadcastRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Transactional lifecycle and immutable audit snapshots for broadcasts. */
@Service
public class BroadcastService {

    private final BroadcastAnnouncementRepository repository;
    private final MutationAuditService mutationAuditService;

    public BroadcastService(
            BroadcastAnnouncementRepository repository,
            MutationAuditService mutationAuditService) {
        this.repository = repository;
        this.mutationAuditService = mutationAuditService;
    }

    @Transactional(readOnly = true)
    public List<BroadcastAnnouncement> active() {
        return CompleteResultGuard.enforce(
                repository.findByEndedAtIsNullAndEndsAtAfterOrderByPublishedAtDesc(
                        TbilisiTime.now(), CompleteResultGuard.sentinelPage()));
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
        mutationAuditService.recordSuccess(
                actor, "PUBLISH_BROADCAST", "broadcast", saved.getId(),
                "Broadcast #" + saved.getId(), null,
                MutationAuditService.broadcastSnapshot(saved));
        return saved;
    }

    @Transactional
    public BroadcastAnnouncement endEarly(Long id, User actor) {
        BroadcastAnnouncement announcement = repository.findById(id)
                .orElseThrow(() -> new BroadcastNotFoundException("განცხადება ვერ მოიძებნა"));
        if (actor.getRole() != Role.SYSTEM_ADMIN && !actor.getId().equals(announcement.getPublishedByUserId())) {
            throw new BroadcastOwnershipException("განცხადების დროზე ადრე მოხსნა მხოლოდ მის გამომქვეყნებელს შეუძლია");
        }

        Map<String, Object> before = MutationAuditService.broadcastSnapshot(announcement);
        OffsetDateTime now = TbilisiTime.now();
        if (announcement.getEndedAt() != null || !announcement.getEndsAt().isAfter(now)) {
            throw new BroadcastStateException("განცხადება უკვე დასრულებულია");
        }
        announcement.setEndedAt(now);
        announcement.setEndedByUserId(actor.getId());
        announcement.setEndedByNameSnapshot(actor.getName());
        BroadcastAnnouncement saved = repository.saveAndFlush(announcement);
        mutationAuditService.recordSuccess(
                actor, "END_BROADCAST_EARLY", "broadcast", saved.getId(),
                "Broadcast #" + saved.getId(), before,
                MutationAuditService.broadcastSnapshot(saved));
        return saved;
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
