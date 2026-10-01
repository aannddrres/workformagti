package ge.magti.portal.compliance;

/**
 * Mirrors the (required_count, read_count, percentage) tuple returned by
 * routers/stats.py's _reading_progress and compliance_utils.py's
 * get_compliance_data_tuple.
 */
public record ReadingProgress(int requiredCount, int readCount, int percentage, int overdueCount) {

    /**
     * Without the overdue count, for callers that never had one. Overdue is
     * owed AND past its deadline -- not the same as unread: "unread" counted
     * as overdue put people on the overdue list a day before their deadline
     * (simulation, 2026-10-01).
     */
    public ReadingProgress(int requiredCount, int readCount, int percentage) {
        this(requiredCount, readCount, percentage, 0);
    }

    public ReadingProgress withOverdue(int overdue) {
        return new ReadingProgress(requiredCount, readCount, percentage, overdue);
    }
}
