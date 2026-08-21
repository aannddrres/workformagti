package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArticleSummaryResponseTest {

    @Test
    void summaryUsesTheRealContentReadingTime() {
        Article article = new Article();
        article.setTitle("გრძელი სტატია");
        article.setContent("სიტყვა ".repeat(450));

        ArticleSummaryResponse summary = ArticleSummaryResponse.from(article, "კატეგორია", List.of("All"));

        assertEquals(3, summary.readTime());
    }
}
