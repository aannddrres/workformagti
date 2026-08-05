package ge.magti.portal.web;

/** Mirrors one entry of get_activity_trend's series list (routers/stats.py:888-891) -- no formal schema in Python. */
public record ActivityPointResponse(String date, long count) {
}
