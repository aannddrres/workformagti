package ge.magti.portal.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two halves, and the second matters as much as the first.
 *
 * <p>Removing script is easy to get right. Not destroying five years of
 * articles while doing it is where a sanitizer actually fails -- silently, on
 * the next save, with no error anywhere. Every format the editor can emit is
 * asserted to survive.
 */
class ContentSanitizerTest {

	// --- what must not survive -------------------------------------------

	@Test
	void dropsScriptElements() {
		String clean = ContentSanitizer.sanitize("<p>hello</p><script>steal(localStorage.magti_token)</script>");
		assertFalse(clean.contains("script"), clean);
		assertTrue(clean.contains("hello"), clean);
	}

	/**
	 * RTA-003's actual payload: no script tag, so a naive tag blocklist misses
	 * it entirely. It fires when Quill writes the body into the DOM.
	 */
	@Test
	void dropsEventHandlerAttributes() {
		String clean = ContentSanitizer.sanitize("<img src=\"/uploads/x.png\" onerror=\"steal()\">");
		assertFalse(clean.contains("onerror"), clean);
		assertFalse(clean.contains("steal"), clean);
	}

	@Test
	void dropsEveryEventHandlerNotJustOnerror() {
		for (String attr : new String[] {"onclick", "onload", "onmouseover", "onfocus", "onanimationstart"}) {
			String clean = ContentSanitizer.sanitize("<p " + attr + "=\"steal()\">text</p>");
			assertFalse(clean.contains(attr), attr + " survived: " + clean);
		}
	}

	/**
	 * preserveRelativeLinks(true) is what keeps /uploads images alive, and the
	 * obvious worry is that it also waves through javascript: URLs. It does
	 * not -- asserted rather than assumed, because the whole allowlist rests
	 * on it.
	 */
	@Test
	void dropsJavascriptUrls() {
		String clean = ContentSanitizer.sanitize("<a href=\"javascript:steal()\">click</a>");
		assertFalse(clean.contains("javascript:"), clean);
		assertTrue(clean.contains("click"), clean);
	}

	@Test
	void dropsFramesAndEmbeds() {
		String clean = ContentSanitizer.sanitize(
				"<iframe src=\"http://evil.example/x\"></iframe>"
						+ "<object data=\"x\"></object><embed src=\"x\">");
		assertFalse(clean.contains("iframe"), clean);
		assertFalse(clean.contains("object"), clean);
		assertFalse(clean.contains("embed"), clean);
	}

	@Test
	void dropsStyleElementsAndInlineStyle() {
		String clean = ContentSanitizer.sanitize(
				"<style>body{display:none}</style><p style=\"background:url(javascript:x)\">t</p>");
		assertFalse(clean.contains("<style"), clean);
		assertFalse(clean.contains("javascript"), clean);
	}

	@Test
	void dropsFormElements() {
		String clean = ContentSanitizer.sanitize(
				"<form action=\"http://evil.example\"><input name=\"password\"></form>");
		assertFalse(clean.contains("<form"), clean);
		assertFalse(clean.contains("<input"), clean);
	}

	// --- what must survive ------------------------------------------------

	/** Every block and inline format the current toolbar, "/" menu and markdown shortcuts can produce. */
	@Test
	void keepsEverythingTheEditorCanEmit() {
		String body = "<h1>სათაური</h1><h2>ქვესათაური</h2><h3>მესამე</h3>"
				+ "<p><strong>მსხვილი</strong> <em>დახრილი</em> <u>ხაზგასმული</u> <s>გადახაზული</s></p>"
				+ "<blockquote>ციტატა</blockquote>"
				+ "<ol><li>ერთი</li></ol><ul><li>ორი</li></ul>"
				+ "<p><a href=\"https://magti.ge\">ბმული</a></p>";
		String clean = ContentSanitizer.sanitize(body);
		for (String fragment : new String[] {
				"<h1>", "<h2>", "<h3>", "<strong>", "<em>", "<u>", "<s>",
				"<blockquote>", "<ol>", "<ul>", "<li>", "href=\"https://magti.ge\"",
				"სათაური", "ციტატა", "ბმული"}) {
			assertTrue(clean.contains(fragment), fragment + " was removed: " + clean);
		}
	}

	/**
	 * The expensive mistake. Inline images are stored relative; jsoup's default
	 * protocol check resolves them against an empty base, fails, and strips the
	 * src -- turning every inline image in the knowledge base into a blank box
	 * on its next save.
	 */
	@Test
	void keepsRelativeUploadImageSources() {
		String clean = ContentSanitizer.sanitize("<p><img src=\"/uploads/6f1c-2b.png\"></p>");
		assertTrue(clean.contains("src=\"/uploads/6f1c-2b.png\""), clean);
	}

	/** Quill encodes nested list depth as a class. Drop it and every nested list flattens. */
	@Test
	void keepsQuillIndentClasses() {
		String clean = ContentSanitizer.sanitize("<ol><li class=\"ql-indent-2\">deep</li></ol>");
		assertTrue(clean.contains("ql-indent-2"), clean);
	}

	/** Bodies written by the Python renderer's fenced-code transform. */
	@Test
	void keepsLegacyCodeBlocks() {
		String clean = ContentSanitizer.sanitize("<pre><code>SELECT 1 FROM dual;</code></pre>");
		assertTrue(clean.contains("<pre>"), clean);
		assertTrue(clean.contains("<code>"), clean);
		assertTrue(clean.contains("SELECT 1 FROM dual;"), clean);
	}

	@Test
	void keepsPastedTables() {
		String clean = ContentSanitizer.sanitize("<table><tbody><tr><td>ა</td></tr></tbody></table>");
		assertTrue(clean.contains("<table>"), clean);
		assertTrue(clean.contains("<td>"), clean);
	}

	/**
	 * jsoup reindents by default. On a body that is already clean that would
	 * still rewrite the string, making the first save after this change look
	 * like a full rewrite in the version history.
	 */
	@Test
	void leavesAlreadyCleanContentByteForByte() {
		String body = "<p>ერთი</p><p>ორი</p><ul><li>სამი</li></ul>";
		assertEquals(body, ContentSanitizer.sanitize(body));
	}

	// --- edges ------------------------------------------------------------

	@Test
	void passesNullAndEmptyThrough() {
		assertEquals(null, ContentSanitizer.sanitize(null));
		assertEquals("", ContentSanitizer.sanitize(""));
	}

	/** A body that is nothing but script sanitizes to empty, not to null. */
	@Test
	void purelyHostileContentBecomesEmptyNotNull() {
		assertEquals("", ContentSanitizer.sanitize("<script>x()</script>"));
	}
}
