package ge.magti.portal.web;

/**
 * Shared request-cardinality contract for legacy offset/limit list endpoints.
 *
 * <p>The Angular client still has explicitly documented fetch-all views that
 * request up to 1,000 article/news summaries. Keeping that ceiling preserves
 * the current UI contract while preventing callers from turning an arbitrary
 * query parameter into an unbounded Oracle result set.
 */
public final class ListQueryBounds {

    public static final int MAX_LIMIT = 1_000;
    public static final String INVALID_DETAIL =
            "skip უნდა იყოს 0 ან მეტი, limit — 1-დან 1000-მდე";

    private ListQueryBounds() {
    }

    public static boolean isInvalid(int skip, int limit) {
        return skip < 0 || limit < 1 || limit > MAX_LIMIT;
    }

}
