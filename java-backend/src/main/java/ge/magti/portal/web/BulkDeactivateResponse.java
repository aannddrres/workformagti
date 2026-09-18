package ge.magti.portal.web;

/**
 * Same shape as {@link BulkRoleReassignResponse}: what actually changed, what
 * was left alone, and how many the administrator asked about -- so a sweep
 * that quietly did less than it looked like says so.
 */
public record BulkDeactivateResponse(
        int deactivated,
        int skipped,
        int requested
) {
}
