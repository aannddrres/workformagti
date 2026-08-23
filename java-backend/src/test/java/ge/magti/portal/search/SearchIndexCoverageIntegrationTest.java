package ge.magti.portal.search;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.repository.ArticleRepository;
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
 * The answer to "did the rebuild finish?" after the request that started it
 * was cut by a proxy.
 *
 * <p>A rebuild at this project's modelled volume takes about twenty minutes
 * (measured: 5.36M rows, 20m47s on Oracle XE 21c). No reverse proxy will hold
 * a request open that long, so the POST's report is the one thing an operator
 * reliably does not get. This reads the same state from the tables instead.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class SearchIndexCoverageIntegrationTest {

    @Autowired private SearchIndexRebuilder rebuilder;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Article article(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შიგთავსი " + title);
        article.setStatus("published");
        article.setCreatedAt(TbilisiTime.now());
        return articleRepository.saveAndFlush(article);
    }

    @Test
    void contentThatWasNeverIndexedReadsAsIncomplete() {
        article("ტარიფის ცვლილება");

        SearchIndexRebuilder.Report coverage = rebuilder.coverage();
        SearchIndexRebuilder.Counted articles = coverage.byEntityType().get(SearchReindexService.ARTICLE);

        assertEquals(1, articles.total());
        assertEquals(0, articles.indexed());
        assertFalse(coverage.complete(), "an empty index after a cutover must not read as complete");
    }

    @Test
    void afterARebuildItReadsAsComplete() {
        article("ტარიფის ცვლილება");
        rebuilder.rebuildAll();

        SearchIndexRebuilder.Report coverage = rebuilder.coverage();

        assertTrue(coverage.complete());
        assertEquals(1, coverage.byEntityType().get(SearchReindexService.ARTICLE).indexed());
        assertEquals(coverage.trigramRows(),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_trigrams", Long.class));
    }

    /** The state an interrupted rebuild leaves, which is the one worth catching. */
    @Test
    void anIndexMissingSomeOfTheContentReadsAsIncomplete() {
        article("პირველი");
        Article second = article("მეორე");
        rebuilder.rebuildAll();

        jdbcTemplate.update("DELETE FROM search_trigrams WHERE entity_type = ? AND entity_id = ?",
                SearchReindexService.ARTICLE, second.getId());

        SearchIndexRebuilder.Report coverage = rebuilder.coverage();

        assertEquals(2, coverage.byEntityType().get(SearchReindexService.ARTICLE).total());
        assertEquals(1, coverage.byEntityType().get(SearchReindexService.ARTICLE).indexed());
        assertFalse(coverage.complete());
    }

    /** Reading the state must never be mistaken for changing it. */
    @Test
    void readingCoverageDoesNotTouchTheIndex() {
        article("ტარიფის ცვლილება");
        long rows = rebuilder.rebuildAll().trigramRows();

        rebuilder.coverage();
        rebuilder.coverage();

        assertEquals(rows, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_trigrams", Long.class));
    }
}
