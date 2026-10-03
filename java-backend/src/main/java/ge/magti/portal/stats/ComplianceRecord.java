package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;

/**
 * One user plus their already-computed {@link ReadingProgress}, as
 * {@code ComplianceQueryService} returns it; a list of these is handed to
 * {@link DepartmentStatsBuilder#build}.
 */
public record ComplianceRecord(User user, ReadingProgress progress) {
}
