package ge.magti.portal.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RTA-011. Two of these assert the security property; the rest assert that
 * fixing it did not quietly break five screens.
 */
class PageBoundsTest {

	@Test
	void anAbsurdLimitIsCappedRatherThanExecuted() {
		assertEquals(PageBounds.MAX_LIMIT, PageBounds.limit(1_000_000));
		assertEquals(PageBounds.MAX_LIMIT, PageBounds.limit(Integer.MAX_VALUE));
	}

	/**
	 * A negative page used to reach setFirstResult/setMaxResults and come back
	 * as a 500. A malformed page number is the caller's mistake, not a fault.
	 */
	@Test
	void negativesNeverReachJpa() {
		assertEquals(1, PageBounds.limit(-1));
		assertEquals(1, PageBounds.limit(Integer.MIN_VALUE));
		assertEquals(0, PageBounds.offset(-1));
		assertEquals(0, PageBounds.offset(Integer.MIN_VALUE));
	}

	/** Zero rows is never what a list screen wants, and JPA treats it as "no limit". */
	@Test
	void zeroLimitBecomesOne() {
		assertEquals(1, PageBounds.limit(0));
	}

	/**
	 * The regression this fix could have caused. The knowledge base, dashboard,
	 * category view, article detail and admin content screens all request 1000
	 * and render everything they get back; a lower cap would hide most of the
	 * knowledge base with nothing failing anywhere.
	 */
	@Test
	void theThousandRowPageEveryListScreenAsksForIsServedInFull() {
		assertEquals(1000, PageBounds.limit(1000),
				"five screens request limit=1000 and have no pagination to fall back on");
	}

	@Test
	void ordinaryPagesArePassedThroughUntouched() {
		for (int value : new int[] {1, 20, 50, 200}) {
			assertEquals(value, PageBounds.limit(value));
		}
		for (int value : new int[] {0, 20, 500}) {
			assertEquals(value, PageBounds.offset(value));
		}
	}
}
