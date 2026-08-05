package ge.magti.portal.web;

/** Mirrors one entry of get_statistics_breakdown's list (routers/stats.py:927) -- no formal schema in Python. */
public record BreakdownItemResponse(String label, long count) {
}
