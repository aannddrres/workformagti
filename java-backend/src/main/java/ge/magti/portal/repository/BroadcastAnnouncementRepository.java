package ge.magti.portal.repository;

import ge.magti.portal.domain.BroadcastAnnouncement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface BroadcastAnnouncementRepository extends JpaRepository<BroadcastAnnouncement, Long> {
    List<BroadcastAnnouncement> findByEndedAtIsNullAndEndsAtAfterOrderByPublishedAtDesc(OffsetDateTime now);
    Page<BroadcastAnnouncement> findAllByOrderByPublishedAtDesc(Pageable pageable);
}
