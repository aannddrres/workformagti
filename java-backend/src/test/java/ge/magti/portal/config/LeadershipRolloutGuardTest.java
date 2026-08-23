package ge.magti.portal.config;

import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one misconfiguration that removes everyone's access at once, made
 * unbootable.
 *
 * <p>Enforcing leadership scope against a database whose backfill has not run
 * is not a degraded state: every non-admin resolves to no scope, so manager
 * statistics and exports return empty, successfully, with nothing in any log
 * that names the cause.
 */
class LeadershipRolloutGuardTest {

	private final PortalProperties properties = new PortalProperties();
	private final LeadershipAssignmentRepository assignments = mock(LeadershipAssignmentRepository.class);
	private final LeadershipRolloutGuard guard = new LeadershipRolloutGuard(properties, assignments);

	@Test
	void enforcingWithNoActiveAssignmentRefusesToStart() {
		properties.getRollout().setLeadershipScopeEnabled(true);
		when(assignments.findByActiveTrue()).thenReturn(List.of());

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> guard.run(null));

		assertTrue(failure.getMessage().contains("read nobody"),
				"the message has to say what the symptom would look like, not just which flag is wrong");
		assertTrue(failure.getMessage().contains("access-diff"),
				"and it has to point at the evidence the cutover gate needs");
	}

	@Test
	void enforcingWithAssignmentsPresentStarts() {
		properties.getRollout().setLeadershipScopeEnabled(true);
		when(assignments.findByActiveTrue()).thenReturn(List.of(new LeadershipAssignment()));

		assertDoesNotThrow(() -> guard.run(null));
	}

	/** With the switch off the guard has no opinion, and must not query anything. */
	@Test
	void theDefaultConfigurationStartsWithoutTouchingTheDatabase() {
		assertDoesNotThrow(() -> guard.run(null));
		verifyNoInteractions(assignments);
	}
}
