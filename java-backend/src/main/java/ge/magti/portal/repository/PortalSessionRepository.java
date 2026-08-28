package ge.magti.portal.repository;

import ge.magti.portal.domain.PortalSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface PortalSessionRepository extends JpaRepository<PortalSession, String> {
    List<PortalSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndLastSeenAtAfterOrderByCreatedAtDesc(
            Long userId, OffsetDateTime now, OffsetDateTime idleCutoff, Pageable pageable);
}
