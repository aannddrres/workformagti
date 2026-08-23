package ge.magti.portal.web;

/**
 * Clamps caller-supplied {@code skip}/{@code limit} at the API boundary.
 *
 * <p><b>RTA-011.</b> Every list endpoint took an {@code int} straight from the
 * query string and handed it to JPA. Two consequences, both reachable by any
 * authenticated employee with a browser address bar:
 *
 * <ul>
 *   <li>{@code ?limit=1000000} made Oracle and Hibernate materialise every
 *       matching row. For articles that means the {@code content} CLOB of each
 *       one, and {@code ArticleSummaryResponse} then walks each full body to
 *       compute a reading time -- so the cost is paid twice, in the database
 *       and in the heap. A handful of concurrent requests is enough to matter.
 *   <li>A negative value threw {@code IllegalArgumentException} out of
 *       {@code setFirstResult}/{@code setMaxResults}, which surfaced as a 500.
 *       A malformed page number is a client mistake, not a server fault.
 * </ul>
 *
 * <p>The Python original did not clamp either, and the service layer's comment
 * recorded that as deliberate port parity. Parity was the right call while the
 * two stacks ran side by side and a difference would have been a portability
 * bug; it stops being the right call once the Python stack is the one being
 * retired. The clamp lives here, at the boundary, so the query services keep
 * describing the query rather than defending against the caller.
 *
 * <p><b>Clamping, not rejecting.</b> An over-large limit returns the first
 * {@link #MAX_LIMIT} rows instead of a 400. These endpoints back list screens
 * that already page; failing the request would turn a harmless bookmark with a
 * stale query string into a broken screen, and the resource exhaustion is
 * equally prevented either way.
 */
public final class PageBounds {

	/**
	 * The largest page any list endpoint will serve.
	 *
	 * <p><b>1000, not the 100 the audit suggested.</b> Five screens -- knowledge
	 * base, dashboard, category view, article detail and admin content -- call
	 * {@code /api/articles} with {@code limit: 1000} and render the whole
	 * result, because they were ported from a Python frontend that did the same
	 * and none of them has real pagination yet. A cap of 100 would have passed
	 * every test in this repository and silently hidden nine tenths of the
	 * knowledge base in production.
	 *
	 * <p>So this bounds the request without changing behaviour the product
	 * depends on: {@code ?limit=1000000} stops being a way to make Oracle
	 * materialise every article's CLOB, which is what the finding was about,
	 * while every real caller is unaffected.
	 *
	 * <p>It is a ceiling, not an endorsement. A thousand full article bodies is
	 * still a large response, and the actual fix is pagination on those five
	 * screens plus a summary projection that never fetches {@code content}.
	 * Lowering this constant is the last step of that work, not the first.
	 */
	public static final int MAX_LIMIT = 1000;

	private PageBounds() {
	}

	/** {@code limit} confined to 1..{@link #MAX_LIMIT}. Zero and negatives become 1. */
	public static int limit(int requested) {
		if (requested < 1) {
			return 1;
		}
		return Math.min(requested, MAX_LIMIT);
	}

	/** {@code skip}/{@code offset} floored at 0, so a negative page never reaches JPA. */
	public static int offset(int requested) {
		return Math.max(requested, 0);
	}
}
