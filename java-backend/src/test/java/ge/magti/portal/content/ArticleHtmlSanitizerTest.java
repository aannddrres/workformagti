package ge.magti.portal.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleHtmlSanitizerTest {

    private final ArticleHtmlSanitizer sanitizer = new ArticleHtmlSanitizer();

    @Test
    void removesExecutableMarkupButKeepsQuillStructure() {
        String clean = sanitizer.sanitize("""
                <h2 class="ql-align-center" onclick="steal()">სათაური</h2>
                <script>alert(1)</script>
                <p style="background:url(javascript:alert(1))">ტექსტი</p>
                <a href="javascript:alert(1)" onmouseover="steal()">ბმული</a>
                <table class="ql-table"><tbody><tr><td>უჯრა</td></tr></tbody></table>
                """);

        assertThat(clean)
                .contains("<h2 class=\"ql-align-center\">სათაური</h2>")
                .contains("<table class=\"ql-table\"><tbody><tr><td>უჯრა</td></tr></tbody></table>")
                .doesNotContain("script", "onclick", "onmouseover", "style=", "javascript:");
    }

    /**
     * The exact battery the 2026-08-31 adversarial round planted in an
     * article as a content editor -- event handlers on images and inputs,
     * an auto-firing SVG/body load, and a nested frame. Every one is an
     * attribute or tag Jsoup's relaxed safelist drops; this pins that so a
     * later safelist tweak that re-admitted any of them would fail the build
     * rather than reach an operator's browser. The benign text around each
     * payload is expected to survive.
     */
    @Test
    void stripsTheAdversarialEventHandlerBattery() {
        String clean = sanitizer.sanitize("""
                <img src="x" onerror="alert('a')">
                <svg onload="alert('b')"></svg>
                <iframe src="javascript:alert('c')"></iframe>
                <body onload="alert('d')">
                <input autofocus onfocus="alert('e')">
                <b>დარჩენადი ტექსტი</b>
                """);

        assertThat(clean)
                .doesNotContain("onerror", "onload", "onfocus", "autofocus",
                        "<svg", "<iframe", "javascript:", "alert")
                .contains("<b>დარჩენადი ტექსტი</b>");
    }

    @Test
    void preservesPrivateRelativeUploadsAndSafeExternalLinks() {
        String clean = sanitizer.sanitize("""
                <p><img src="/uploads/guide.png" alt="გზამკვლევი" width="640"></p>
                <p><a href="https://www.magticom.ge/" target="_blank">Magti</a></p>
                """);

        assertThat(clean)
                .contains("src=\"/uploads/guide.png\"")
                .contains("href=\"https://www.magticom.ge/\"")
                .contains("target=\"_blank\"");
    }
}
