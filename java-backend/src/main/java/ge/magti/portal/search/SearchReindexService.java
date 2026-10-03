package ge.magti.portal.search;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.SearchTrigramRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Write-side half of the hand-built search index: keeps {@code
 * search_trigrams} in sync whenever an Article/News/VideoInstruction's
 * searchable text (title/content/tags/category) changes. Called explicitly
 * from the relevant controller write-paths -- not a JPA entity-lifecycle
 * listener, matching this port's established preference for explicit
 * service calls over ORM-level magic.
 *
 * <p>Only wired into endpoints that can actually change title/content/tags/
 * category -- archive/unarchive/note/verify/read-receipt/view
 * endpoints don't touch those fields, so they don't need a reindex call.
 */
@Service
public class SearchReindexService {

    public static final String ARTICLE = "ARTICLE";
    public static final String NEWS = "NEWS";
    public static final String VIDEO = "VIDEO";

    private final SearchTrigramRepository repository;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SearchReindexService(SearchTrigramRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** Indexes title + content + tags, the fields the article search matches words against. */
    public void reindexArticle(Article article) {
        reindex(ARTICLE, article.getId(), join(article.getTitle(), article.getContent(), article.getTags()));
    }

    /** Indexes title + content, the fields the news search matches words against. */
    public void reindexNews(News news) {
        reindex(NEWS, news.getId(), join(news.getTitle(), news.getContent()));
    }

    /** Indexes title + category, the fields the video search matches words against. */
    public void reindexVideo(VideoInstruction video) {
        reindex(VIDEO, video.getId(), join(video.getTitle(), video.getCategory()));
    }

    public void remove(String entityType, Long entityId) {
        repository.deleteByEntityTypeAndEntityId(entityType, entityId);
    }

    private void reindex(String entityType, Long entityId, String combinedText) {
        repository.deleteByEntityTypeAndEntityId(entityType, entityId);
        Set<String> trigrams = TrigramIndexer.extract(combinedText);
        if (trigrams.isEmpty()) {
            return;
        }
        // One JDBC batch, not saveAll: the id is an IDENTITY column, which
        // turns Hibernate's batching off, so a long article's ~6,000 trigrams
        // were ~6,000 round trips -- a 4 s save, 4.6 s at peak load (QA round
        // 5). Same rows, same transaction (JdbcTemplate joins the JPA one).
        List<Object[]> rows = new ArrayList<>(trigrams.size());
        for (String trigram : trigrams) {
            rows.add(new Object[] {entityType, entityId, trigram});
        }
        jdbc.batchUpdate("INSERT INTO search_trigrams (entity_type, entity_id, trigram) VALUES (?, ?, ?)", rows);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null) {
                sb.append(part).append(' ');
            }
        }
        return sb.toString();
    }
}
