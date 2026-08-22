package ge.magti.portal.web;

/** Legacy and proposed compliance eligibility, with no historical records. */
public record AccessDiffComplianceResponse(boolean legacy, boolean proposed) {
}
