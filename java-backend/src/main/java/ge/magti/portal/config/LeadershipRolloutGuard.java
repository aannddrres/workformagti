package ge.magti.portal.config;

import ge.magti.portal.repository.LeadershipAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start an application that enforces leadership scope against a
 * database that has no leadership in it.
 *
 * <p>Scope comes from {@code leadership_assignments}: a caller with no active
 * assignment resolves to {@link ge.magti.portal.security.Scope#none()} and
 * reads nobody. That is the correct rule and the whole point of the model --
 * but it means switching {@code ROLLOUT_LEADERSHIP_SCOPE} on before the Phase 2
 * backfill has run does not degrade access, it removes it: every manager
 * screen and every export goes empty simultaneously, with no error anywhere to
 * explain why. The symptom looks like a data-loss incident and would be
 * debugged as one.
 *
 * <p>So the crude case is made unbootable. This is the same shape as
 * {@link ProductionSafetyGuard}: a misconfiguration that is silent at runtime
 * becomes loud at startup, before anyone depends on it.
 *
 * <p><b>What this guard does not do.</b> It counts assignments; it cannot tell
 * a complete backfill from a partial one. A database with three assignments
 * for thirty groups passes here and still closes access for twenty-seven of
 * them. The evidence for that is {@code GET /api/admin/access-diff}, which
 * lists per user what the switch would change, and the plan makes reading it a
 * gate on the cutover rather than a formality. A guard is a floor, not the
 * gate.
 */
@Component
public class LeadershipRolloutGuard implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(LeadershipRolloutGuard.class);

	private final PortalProperties properties;
	private final LeadershipAssignmentRepository assignments;

	public LeadershipRolloutGuard(
			PortalProperties properties, LeadershipAssignmentRepository assignments) {
		this.properties = properties;
		this.assignments = assignments;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.getRollout().isLeadershipScopeEnabled()) {
			logger.info(
					"Leadership scope is OFF: scoped reads use the legacy department rule. "
							+ "The new rule still runs beside it and its disagreements are counted "
							+ "(see GET /api/admin/policy-shadow).");
			return;
		}

		long active = assignments.findByActiveTrue().size();
		if (active == 0) {
			throw new IllegalStateException(
					"ROLLOUT_LEADERSHIP_SCOPE is enabled but leadership_assignments holds no active row. "
							+ "Every non-admin caller would resolve to no scope and read nobody: "
							+ "manager statistics and exports would all return empty with no error. "
							+ "Run the approved Phase 2 leadership backfill first, review "
							+ "GET /api/admin/access-diff, and only then enable this switch.");
		}
		logger.warn(
				"Leadership scope is ENFORCED against {} active assignment(s). Callers without one "
						+ "read nobody by design -- confirm GET /api/admin/access-diff was reviewed.",
				active);
	}
}
