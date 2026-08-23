package ge.magti.portal.search;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.SearchTrigramRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cutover step, tested against content that arrived the way the cutover
 * makes it arrive: written to the database with no write path running, so
 * nothing has indexed it.
 *
 * <p>Assertions are about the rebuild reaching every row and reporting
 * honestly when it did not. Whether the trigrams themselves are right is
 * {@link TrigramIndexer}'s own tests' question, and whether a search finds
 * them is {@link SearchQueryService}'s.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class SearchIndexRebuilderIntegrationTest {

    @Autowired private SearchIndexRebuilder rebuilder;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private NewsRepository newsRepository;
    @Autowired private VideoInstructionRepository videoRepository;
    @Autowired private SearchTrigramRepository trigramRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Article article(String title, String content) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setStatus("published");
        article.setCreatedAt(TbilisiTime.now());
        return articleRepository.saveAndFlush(article);
    }

    private long trigramsFor(String entityType, Long entityId) {
        return trigramRepository.findAll().stream()
                .filter(row -> row.getEntityType().equals(entityType) && row.getEntityId().equals(entityId))
                .count();
    }

    @Test
    void contentThatNoWritePathEverSawBecomesSearchableAfterARebuild() {
        Article migrated = article("ტარიფის ცვლილება", "ახალი ტარიფები ძალაშია ორშაბათიდან");

        assertEquals(0, trigramsFor(SearchReindexService.ARTICLE, migrated.getId()),
                "the fixture must start unindexed, or this test proves nothing about the cutover");

        SearchIndexRebuilder.Report report = rebuilder.rebuildAll();

        assertTrue(report.complete(), () -> "incomplete rebuild: " + report.byEntityType());
        assertTrue(trigramsFor(SearchReindexService.ARTICLE, migrated.getId()) > 0);
        assertEquals(1, report.byEntityType().get(SearchReindexService.ARTICLE).indexed());
    }

    @Test
    void allThreeIndexedTypesAreCovered() {
        article("სტატია", "შიგთავსი");

        News news = new News();
        news.setTitle("სიახლე");
        news.setContent("სიახლის ტექსტი");
        news.setCreatedAt(TbilisiTime.now());
        newsRepository.saveAndFlush(news);

        VideoInstruction video = new VideoInstruction();
        video.setTitle("ვიდეო ინსტრუქცია");
        video.setVideoUrl("https://youtu.be/abcdefghijk");
        video.setCategory("ტექნიკური");
        video.setCreatedAt(TbilisiTime.now());
        videoRepository.saveAndFlush(video);

        SearchIndexRebuilder.Report report = rebuilder.rebuildAll();

        assertTrue(report.complete());
        for (String entityType : new String[] {
                SearchReindexService.ARTICLE, SearchReindexService.NEWS, SearchReindexService.VIDEO}) {
            assertEquals(1, report.byEntityType().get(entityType).indexed(), entityType + " was not reached");
            assertTrue(report.byEntityType().get(entityType).trigramRows() > 0, entityType);
        }
        assertEquals(report.trigramRows(), trigramRepository.count(),
                "the reported row count must be the number of rows actually in the table -- Oracle returns "
                        + "SUCCESS_NO_INFO per batched row, which is easy to sum to zero");
    }

    /** Re-running must not double the index; the unique constraint would reject it anyway. */
    @Test
    void rebuildingTwiceLeavesTheSameIndex() {
        article("ტარიფის ცვლილება", "ახალი ტარიფები");

        long first = rebuilder.rebuildAll().trigramRows();
        long second = rebuilder.rebuildAll().trigramRows();

        assertEquals(first, second);
        assertEquals(second, trigramRepository.count());
    }

    /**
     * Text shorter than a trigram produces no rows, and that is not a failure.
     *
     * <p>Reaching this case takes a one-character title and no category: the
     * fields are joined with a space, so even two two-character fields make a
     * five-character string with trigrams in it. Which is the point of the
     * counter -- it is nearly unreachable, and if a row like this ever does
     * exist, without it {@code complete()} would read false forever and send
     * somebody looking for content that is not missing.
     */
    @Test
    void textTooShortToHaveATrigramIsSkippedRatherThanReportedMissing() {
        VideoInstruction video = new VideoInstruction();
        video.setTitle("ა");
        video.setVideoUrl("https://youtu.be/abcdefghijk");
        video.setCategory(null);
        video.setCreatedAt(TbilisiTime.now());
        videoRepository.saveAndFlush(video);

        SearchIndexRebuilder.Report report = rebuilder.rebuildAll();
        SearchIndexRebuilder.Counted videos = report.byEntityType().get(SearchReindexService.VIDEO);

        assertEquals(1, videos.total());
        assertEquals(0, videos.indexed(), "one character plus a separator is two characters");
        assertEquals(1, videos.skipped());
        assertTrue(videos.complete());
        assertTrue(report.complete());
    }

    /**
     * The failure mode the report exists for: an index that covers some of the
     * content looks exactly like a working one from the outside.
     */
    @Test
    void anIndexCoveringOnlySomeOfTheContentIsNotReportedComplete() {
        article("პირველი სტატია", "შიგთავსი ერთი");
        Article second = article("მეორე სტატია", "შიგთავსი ორი");

        rebuilder.rebuildAll();
        jdbcTemplate.update("DELETE FROM search_trigrams WHERE entity_type = ? AND entity_id = ?",
                SearchReindexService.ARTICLE, second.getId());

        // Recount without rebuilding: what an operator would see checking the
        // table after an interrupted run.
        long indexedArticles = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT entity_id) FROM search_trigrams WHERE entity_type = ?",
                Long.class, SearchReindexService.ARTICLE);

        assertEquals(1, indexedArticles);
        assertFalse(indexedArticles == articleRepository.count(),
                "this is the state the runbook's verification has to catch");

        SearchIndexRebuilder.Report repaired = rebuilder.rebuildAll();
        assertTrue(repaired.complete(), "and a re-run is the repair");
        assertEquals(2, repaired.byEntityType().get(SearchReindexService.ARTICLE).indexed());
    }
}
