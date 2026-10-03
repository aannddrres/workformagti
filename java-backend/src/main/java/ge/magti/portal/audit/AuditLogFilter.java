package ge.magti.portal.audit;

/**
 * The filter parameters the audit-log list and its export share, consumed
 * by AuditLogQueryService.buildWhere.
 */
public record AuditLogFilter(
        String startDate,
        String endDate,
        Long userId,
        String userName,
        String action,
        String category,
        String q) {
}
