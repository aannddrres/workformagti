package ge.magti.portal.web;

import ge.magti.portal.article.ArticleListItem;
import ge.magti.portal.domain.Article;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
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

    @Test
    void listProjectionUsesTheDatabaseMaintainedReadingTime() {
        ArticleListItem item = new ArticleListItem(
                7L, "გრძელი სტატია", 8L, "tag", "published",
                OffsetDateTime.parse("2026-08-25T10:00:00Z"),
                OffsetDateTime.parse("2026-08-25T09:00:00Z"),
                4, "all", true, false, false);

        ArticleSummaryResponse summary = ArticleSummaryResponse.from(
                item, "კატეგორია", List.of("All"));

        assertEquals(4, summary.readTime());
        assertEquals(7L, summary.id());
    }
}
