package ge.magti.portal.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Strips executable markup out of rich-text bodies on the way in.
 *
 * <p><b>Why the server, when Angular already sanitizes?</b> Reading an article
 * is safe: {@code format-article-content.ts} binds the body through
 * {@code [innerHTML]}, so Angular's own DomSanitizer runs with no bypass.
 * <b>Editing one is not.</b> {@code rich-text-editor.ts}'s {@code setHtml}
 * hands the stored string to Quill's {@code dangerouslyPasteHTML}, which
 * writes it into the DOM without passing through Angular at all. A content
 * administrator could therefore store {@code <img onerror=...>} and have it
 * execute in a system administrator's browser the moment they opened the
 * article to review it -- with the bearer token in localStorage right there
 * (audit 3, RTA-003).
 *
 * <p>The client-side half of that fix is one call to DOMPurify in
 * {@code setHtml}, and it is what protects bodies stored before today. This
 * class is the other half: content that never becomes hostile in the database
 * cannot be served to a future client that forgets to sanitize, or to an
 * export, or to a PDF renderer.
 *
 * <p><b>The allowlist is deliberately wider than the editor's toolbar.</b>
 * The toolbar emits h1-h3, bold/italic/underline/strike, ordered and bullet
 * lists, links and images, plus blockquote from the "/" menu and the "&gt; "
 * shortcut. But five years of articles came through the Python app, whose
 * renderer also produced {@code pre}/{@code code} from fenced blocks, and
 * pasted content brings tables. Sanitizing on write means a body that loses a
 * tag here loses it <b>permanently</b> -- so this starts from jsoup's
 * {@code relaxed} set, which already excludes every element that can run
 * script ({@code script}, {@code style}, {@code iframe}, {@code object},
 * {@code embed}, {@code form}), rather than from a list of what the current
 * toolbar happens to emit.
 *
 * <p>Three deliberate additions to {@code relaxed}:
 *
 * <ul>
 *   <li>{@code class} on every element. Quill encodes list indentation as
 *       {@code class="ql-indent-2"}; dropping it would silently flatten every
 *       nested list in the knowledge base.
 *   <li>{@code s}, which {@code relaxed} omits -- it allows only the older
 *       {@code strike}. Quill emits {@code <s>} for strikethrough, so without
 *       this every struck-through passage in the knowledge base would lose its
 *       formatting on the next save. Caught by
 *       {@code keepsEverythingTheEditorCanEmit}, which is why that test
 *       enumerates the whole toolbar instead of spot-checking it.
 *   <li>{@link Safelist#preserveRelativeLinks(boolean)} <b>together with a base
 *       URI</b>. Inline images are stored as {@code /uploads/<uuid>.png} --
 *       relative. The flag alone is not enough, and it fails silently: jsoup
 *       still resolves every URL against the base to confirm its protocol, and
 *       against an empty base a relative path resolves to nothing, so the
 *       attribute is dropped. The first version of this class did exactly that
 *       and deleted the {@code src} of every inline image. {@link #BASE_URI}
 *       gives that resolution something to succeed against; the flag then keeps
 *       the original relative form in the output, so the base never appears in
 *       stored content.
 * </ul>
 *
 * <p>Resolving against a base does not weaken the protocol check:
 * {@code javascript:steal()} carries its own scheme, resolves to itself, and is
 * still removed. {@code dropsJavascriptUrls} asserts that rather than assuming
 * it, because the whole allowlist rests on it.
 *
 * <p>Event-handler attributes ({@code onerror}, {@code onclick}, ...) are not
 * on any safelist, so they are dropped for every element without being
 * enumerated here.
 */
public final class ContentSanitizer {

	/**
	 * Built once: {@code Safelist} is immutable after configuration and
	 * {@code Jsoup.clean} does not mutate it.
	 */
	private static final Safelist SAFELIST = Safelist.relaxed()
			.addTags("s")
			.addAttributes(":all", "class")
			.preserveRelativeLinks(true);

	/**
	 * Only ever used to resolve a relative URL far enough to check its protocol.
	 * It never reaches the output -- {@code preserveRelativeLinks(true)} restores
	 * the original form -- and {@code keepsRelativeUploadImageSources} asserts
	 * exactly that.
	 */
	private static final String BASE_URI = "https://portal.invalid/";

	/**
	 * prettyPrint off, or jsoup reindents every body it touches. That would
	 * turn the first save after this change into a whole-document diff in the
	 * version history, and make {@code HtmlDiffer}'s output useless for the
	 * one revision people would most want to read.
	 */
	private static final Document.OutputSettings OUTPUT =
			new Document.OutputSettings().prettyPrint(false);

	private ContentSanitizer() {
	}

	/**
	 * Returns {@code html} with script-bearing markup removed.
	 *
	 * <p>{@code null} in, {@code null} out: a body that was never supplied is
	 * different from one that sanitized down to nothing, and the callers'
	 * "field absent" handling depends on that difference.
	 */
	public static String sanitize(String html) {
		if (html == null || html.isEmpty()) {
			return html;
		}
		return Jsoup.clean(html, BASE_URI, SAFELIST, OUTPUT);
	}
}
