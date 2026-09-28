package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.PortalSession;
import ge.magti.portal.repository.PortalSessionRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ASVS V7.3: the two server-side session limits, 30 minutes idle and 8 hours
 * in all (PortalProperties.Session). The browser's idle timer is a courtesy
 * (idle-session.service.spec.ts); these are what hold when the browser does
 * not cooperate, because every request's token is checked here.
 */
class PortalSessionServiceTest {

    private static final long USER = 7L;

    private PortalSessionRepository repository;
    private PortalSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PortalSessionRepository.class);
        service = new PortalSessionService(repository, new PortalProperties());
    }

    private PortalSession stored(OffsetDateTime lastSeen, OffsetDateTime expires) {
        PortalSession session = new PortalSession();
        session.setId("session-1");
        session.setUserId(USER);
        session.setCreatedAt(lastSeen.minusMinutes(1));
        session.setLastSeenAt(lastSeen);
        session.setExpiresAt(expires);
        when(repository.findById("session-1")).thenReturn(Optional.of(session));
        return session;
    }

    @Test
    void aSessionInUseIsAcceptedAndTouchedAtMostOncePerMinute() {
        OffsetDateTime now = TbilisiTime.now();
        stored(now.minusSeconds(10), now.plusHours(1));
        assertTrue(service.validateAndTouch("session-1", USER));
        verify(repository, never()).save(any());

        PortalSession older = stored(now.minusMinutes(2), now.plusHours(1));
        assertTrue(service.validateAndTouch("session-1", USER));
        verify(repository).save(older);
    }

    @Test
    void aSessionIdleLongerThanTheLimitIsRefused() {
        OffsetDateTime now = TbilisiTime.now();
        stored(now.minusMinutes(31), now.plusHours(4));

        assertFalse(service.validateAndTouch("session-1", USER));
    }

    @Test
    void aSessionPastItsAbsoluteLifetimeIsRefusedEvenWhileInUse() {
        OffsetDateTime now = TbilisiTime.now();
        stored(now.minusSeconds(5), now.minusSeconds(1));

        assertFalse(service.validateAndTouch("session-1", USER));
    }

    @Test
    void aRevokedSessionIsRefused() {
        OffsetDateTime now = TbilisiTime.now();
        stored(now.minusSeconds(5), now.plusHours(1)).setRevokedAt(now.minusSeconds(1));

        assertFalse(service.validateAndTouch("session-1", USER));
    }

    @Test
    void anotherUsersSessionIsRefused() {
        OffsetDateTime now = TbilisiTime.now();
        stored(now.minusSeconds(5), now.plusHours(1));

        assertFalse(service.validateAndTouch("session-1", USER + 1));
    }

    @Test
    void aNewSessionEndsEightHoursAfterItStarts() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ge.magti.portal.domain.User user = new ge.magti.portal.domain.User();
        user.setId(USER);

        PortalSession created = service.create(user, "10.0.0.1", "test-agent");

        assertTrue(created.getExpiresAt().isEqual(created.getCreatedAt().plusMinutes(480)));
        assertFalse(created.getId().isBlank());
    }
}
