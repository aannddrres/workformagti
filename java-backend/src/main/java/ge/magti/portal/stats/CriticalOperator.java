package ge.magti.portal.stats;

/** Mirrors the per-operator dict routers/stats.py's get_critical_operators builds (routers/stats.py:731-737). */
public record CriticalOperator(Long userId, String firstName, String lastName, String department, int overdueCount) {
}
