package ge.magti.portal.web;

/** Mutually exclusive, loss-first classification across every stored user. */
public record AccessDiffTotalsResponse(long users, long gains, long losses, long unchanged) {
}
