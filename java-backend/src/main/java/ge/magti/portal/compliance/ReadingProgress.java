package ge.magti.portal.compliance;

/**
 * One user's (required_count, read_count, percentage) reading progress.
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

    /**
     * "Critical": owes something, and is below
     * {@link ComplianceCalculator#CRITICAL_THRESHOLD} or has anything past its
     * deadline. One rule for the leader's list and the dashboard tile that
     * opens it -- they were two, and the tile said 0 over a list of one
     * (RoleFlowIntegrationTest, 2026-10-01).
     */
    public boolean critical() {
        return requiredCount > 0 && (percentage < ComplianceCalculator.CRITICAL_THRESHOLD || overdueCount > 0);
    }
}
