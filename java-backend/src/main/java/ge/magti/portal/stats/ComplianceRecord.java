package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;

/**
 * Mirrors one entry of the list routers/stats.py's compute_compliance
 * returns (routers/stats.py:325-337) -- one user plus their already-computed
 * {@link ReadingProgress}. compute_compliance() itself is a DB query and is
 * not ported; whoever wires a repository builds this list, then hands it to
 * {@link DepartmentStatsBuilder#build}.
 */
public record ComplianceRecord(User user, ReadingProgress progress) {
}
