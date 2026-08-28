package ge.magti.portal.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Canonical server-side sanitizer for article and news rich text.
 *
 * <p>The browser sanitizer remains defence in depth; this boundary is the
 * authority because content may also be written by scripts or future clients.
 * Quill's semantic classes are retained, while scripts, event handlers,
 * inline styles and unsafe URL protocols are discarded.
 */
@Component
public final class ArticleHtmlSanitizer {

    private static final Safelist CONTENT_SAFELIST = Safelist.relaxed()
            .addAttributes(":all", "class")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "alt", "height", "title", "width")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true);

    private static final Document.OutputSettings OUTPUT_SETTINGS = new Document.OutputSettings()
            .prettyPrint(false);

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return html == null ? null : "";
        }
        // A non-empty same-origin base lets jsoup validate relative /uploads
        // URLs against the HTTPS allowlist. preserveRelativeLinks keeps the
        // stored value relative instead of rewriting it to this sentinel.
        return Jsoup.clean(html, "https://portal.invalid", CONTENT_SAFELIST, OUTPUT_SETTINGS);
    }
}
