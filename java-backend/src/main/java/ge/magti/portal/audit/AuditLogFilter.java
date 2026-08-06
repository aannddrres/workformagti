package ge.magti.portal.audit;

/**
 * Mirrors the shared filter parameters of get_audit_logs and
 * export_audit_logs (routers/audit_logs.py:179-260), consumed by
 * AuditLogQueryService.buildWhere -- the Java equivalent of
 * _build_audit_query's conditional {@code .filter(...)} chain.
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
