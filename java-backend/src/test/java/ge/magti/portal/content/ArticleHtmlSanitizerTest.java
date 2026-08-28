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
