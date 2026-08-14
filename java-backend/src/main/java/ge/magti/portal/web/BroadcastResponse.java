package ge.magti.portal.web;

/** POST /api/broadcast's response -- {@code recipients} is the count of
 *  {@link ge.magti.portal.domain.Message} rows actually created (2026-08-14
 *  fix, see MessagingController's javadoc). */
public record BroadcastResponse(String status, int recipients) {
}
