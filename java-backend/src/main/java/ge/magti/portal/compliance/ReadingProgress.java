package ge.magti.portal.compliance;

/**
 * Mirrors the (required_count, read_count, percentage) tuple returned by
 * routers/stats.py's _reading_progress and compliance_utils.py's
 * get_compliance_data_tuple.
 */
public record ReadingProgress(int requiredCount, int readCount, int percentage) {
}
