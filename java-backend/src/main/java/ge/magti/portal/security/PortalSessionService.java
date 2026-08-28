package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.PortalSession;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.PortalSessionRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PortalSessionService {
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final PortalSessionRepository repository;
    private final PortalProperties.Session config;

    public PortalSessionService(PortalSessionRepository repository, PortalProperties properties) {
        this.repository = repository;
        this.config = properties.getSecurity().getSession();
    }

    @Transactional
    public PortalSession create(User user, String clientIp, String userAgent) {
        OffsetDateTime now = TbilisiTime.now();
        PortalSession session = new PortalSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plusMinutes(config.getMaximumMinutes()));
        session.setClientIp(clientIp);
        session.setUserAgent(userAgent);
        return repository.saveAndFlush(session);
    }

    @Transactional
    public boolean validateAndTouch(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        PortalSession session = repository.findById(sessionId).orElse(null);
        OffsetDateTime now = TbilisiTime.now();
        if (session == null || !session.getUserId().equals(userId) || session.getRevokedAt() != null
                || !session.getExpiresAt().isAfter(now)
                || !session.getLastSeenAt().plusMinutes(config.getIdleMinutes()).isAfter(now)) {
            return false;
        }
        if (!session.getLastSeenAt().plus(TOUCH_INTERVAL).isAfter(now)) {
            session.setLastSeenAt(now);
            repository.save(session);
        }
        return true;
    }

    @Transactional
    public boolean revoke(String sessionId, Long userId) {
        PortalSession session = repository.findById(sessionId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .filter(candidate -> candidate.getRevokedAt() == null)
                .orElse(null);
        if (session == null) {
            return false;
        }
        session.setRevokedAt(TbilisiTime.now());
        repository.saveAndFlush(session);
        return true;
    }

    @Transactional(readOnly = true)
    public List<PortalSession> list(Long userId) {
        OffsetDateTime now = TbilisiTime.now();
        OffsetDateTime idleCutoff = now.minusMinutes(config.getIdleMinutes());
        return CompleteResultGuard.enforce(repository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndLastSeenAtAfterOrderByCreatedAtDesc(
                        userId, now, idleCutoff, CompleteResultGuard.sentinelPage()));
    }
}
