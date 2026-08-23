package ge.magti.portal.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds {@code search_trigrams} from the content that is actually in the
 * database.
 *
 * <p>{@link SearchReindexService} keeps the index current one item at a time,
 * called from the write paths that change searchable text. That covers every
 * way content changes through the product -- and misses the one way it changes
 * during a cutover, which is the ETL writing rows straight into Oracle. After
 * the migration the articles are all there and {@code search_trigrams} is
 * empty, so search answers 200 OK with no results for every word in the
 * knowledge base. Nothing fails, nothing is logged, and the report is "search
 * is broken" some days later.
 *
 * <p>{@code scripts/etl/spec.py} already refuses to copy the trigram rows,
 * correctly -- stale trigrams that disagree with the loaded content would be
 * worse than none -- and says the indexer rebuilds them. This is the thing it
 * was describing, which did not exist.
 *
 * <p><b>Indexes every row, exactly like the incremental path.</b> The trigram
 * table is only a candidate pre-filter: {@link SearchQueryService} applies
 * status, expiry and department visibility afterwards, from the entity rows
 * themselves. Filtering here would mean an archived article that is later
 * republished stays invisible to search until someone edits it again.
 *
 * <p><b>Each batch commits, explicitly.</b> Two reasons, and the second is
 * the one that bites. Millions of inserts in a single transaction is an
 * undo-tablespace question, and the answer during a change window should not
 * be "it depends how big your UNDO is". More importantly, leaving the commit
 * to the connection's autocommit setting means leaving it to the pool: a
 * measurement of this class writing several million rows through a plain
 * {@code JdbcTemplate} found every one of them invisible to any other session
 * and gone when the connection went back to the pool. A rebuild that reports
 * five million rows and leaves the index empty is worse than no rebuild.
 * {@link TransactionTemplate} at default propagation settles it: outside a
 * transaction each batch gets one of its own and commits; inside one (a test)
 * it joins, so the test still rolls back.
 *
 * <p>Committing per batch means a failure leaves a partial index -- so the
 * report says how many of each entity were indexed against how many exist, and
 * {@link Report#complete()} is the field to read. A partial index is the
 * dangerous outcome precisely because it looks like a working one.
 */
@Service
public class SearchIndexRebuilder {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexRebuilder.class);

    /** Rows per JDBC batch. Big enough to amortise round-trips, small enough to bound the heap. */
    private static final int BATCH_ROWS = 5_000;

    /**
     * How often to log progress. A rebuild at this project's modelled volume
     * runs for minutes, not seconds -- a long silence is indistinguishable
     * from a hang to whoever is waiting on it during a change window.
     */
    private static final int LOG_EVERY = 100;

    private static final String INSERT =
            "INSERT INTO search_trigrams (entity_type, entity_id, trigram) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SearchIndexRebuilder(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Per entity type.
     *
     * @param skipped rows whose searchable text is shorter than a trigram.
     *                They produce no rows and are not a failure -- without
     *                counting them separately, {@code indexed < total} would
     *                make every clean rebuild look partial.
     */
    public record Counted(long total, long indexed, long skipped, long trigramRows) {
        public boolean complete() {
            return indexed + skipped == total;
        }
    }

    public record Report(Map<String, Counted> byEntityType, long trigramRows, long tookMillis) {
        /** The field to read: false means some content is not searchable. */
        public boolean complete() {
            return byEntityType.values().stream().allMatch(Counted::complete);
        }
    }

    /**
     * How much of the content is indexed right now, without touching it.
     *
     * <p>Exists because {@link #rebuildAll()} runs for minutes at this
     * project's modelled volume, and an HTTP call that runs for minutes gets
     * cut by a reverse proxy long before it answers. The work continues --
     * batches commit as they go -- but the report is lost, and the operator is
     * left without the one field that matters. This answers the same question
     * from the tables, any time, in milliseconds.
     *
     * <p>{@code indexed} counts entities with at least one trigram row.
     * Nothing here can tell an entity that was skipped for having no trigram
     * from one the rebuild never reached, which is what {@link Counted#skipped}
     * in a live report distinguishes -- so {@code complete()} on this snapshot
     * is the pessimistic reading: it treats every unindexed row as missing.
     * On real content that is the correct reading, because text shorter than
     * three characters does not occur.
     */
    public Report coverage() {
        long startedAt = System.nanoTime();
        Map<String, Counted> byEntityType = new LinkedHashMap<>();
        byEntityType.put(SearchReindexService.ARTICLE, count(SearchReindexService.ARTICLE, "articles"));
        byEntityType.put(SearchReindexService.NEWS, count(SearchReindexService.NEWS, "news"));
        byEntityType.put(SearchReindexService.VIDEO, count(SearchReindexService.VIDEO, "video_instructions"));

        long trigramRows = byEntityType.values().stream().mapToLong(Counted::trigramRows).sum();
        return new Report(Map.copyOf(byEntityType), trigramRows, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private Counted count(String entityType, String table) {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        Long indexed = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT entity_id) FROM search_trigrams WHERE entity_type = ?",
                Long.class, entityType);
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_trigrams WHERE entity_type = ?", Long.class, entityType);
        return new Counted(orZero(total), orZero(indexed), 0, orZero(rows));
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    public Report rebuildAll() {
        long startedAt = System.nanoTime();
        logger.warn("Search index rebuild starting: clearing search_trigrams");
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("DELETE FROM search_trigrams"));

        Map<String, Counted> byEntityType = new LinkedHashMap<>();
        byEntityType.put(SearchReindexService.ARTICLE, index(SearchReindexService.ARTICLE,
                "SELECT id, title, content, tags FROM articles ORDER BY id", 3));
        byEntityType.put(SearchReindexService.NEWS, index(SearchReindexService.NEWS,
                "SELECT id, title, content FROM news ORDER BY id", 2));
        byEntityType.put(SearchReindexService.VIDEO, index(SearchReindexService.VIDEO,
                "SELECT id, title, category FROM video_instructions ORDER BY id", 2));

        long trigramRows = byEntityType.values().stream().mapToLong(Counted::trigramRows).sum();
        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;
        Report report = new Report(Map.copyOf(byEntityType), trigramRows, tookMillis);
        logger.warn("Search index rebuild finished in {} ms: {} trigram row(s), complete={}",
                tookMillis, trigramRows, report.complete());
        return report;
    }

    /**
     * @param textColumns how many columns after the id hold searchable text,
     *                    so the three queries above can share one reader
     */
    private Counted index(String entityType, String select, int textColumns) {
        long[] counts = new long[4]; // total, indexed, skipped, rows
        List<Object[]> pending = new ArrayList<>();

        jdbcTemplate.query(select, resultSet -> {
            long id = resultSet.getLong(1);
            StringBuilder text = new StringBuilder();
            for (int column = 2; column <= textColumns + 1; column++) {
                // getString reads a CLOB in full through ojdbc; articles.content
                // is the only one large enough for that to be a question, and
                // the alternative (a streamed Reader) would buy nothing because
                // trigram extraction needs the whole text anyway.
                String part = resultSet.getString(column);
                if (part != null) {
                    text.append(part).append(' ');
                }
            }

            counts[0]++;
            if (counts[0] % LOG_EVERY == 0) {
                logger.info("Search index rebuild: {} {} processed, {} trigram row(s) written",
                        counts[0], entityType, counts[3]);
            }
            Set<String> trigrams = TrigramIndexer.extract(text.toString());
            if (trigrams.isEmpty()) {
                counts[2]++;
                return;
            }
            counts[1]++;
            for (String trigram : trigrams) {
                pending.add(new Object[] {entityType, id, trigram});
            }
            if (pending.size() >= BATCH_ROWS) {
                counts[3] += flush(pending);
            }
        });
        counts[3] += flush(pending);

        return new Counted(counts[0], counts[1], counts[2], counts[3]);
    }

    private long flush(List<Object[]> pending) {
        if (pending.isEmpty()) {
            return 0;
        }
        int written = transactionTemplate.execute(status -> {
            int rows = 0;
            for (int applied : jdbcTemplate.batchUpdate(INSERT, pending)) {
                // Oracle reports SUCCESS_NO_INFO (-2) per row in a batch rather
                // than a count, so a negative result is a written row, not a
                // failed one -- summing the raw values would report 0 rows
                // written for a batch that wrote every one of them.
                rows += applied == java.sql.Statement.SUCCESS_NO_INFO ? 1 : applied;
            }
            return rows;
        });
        pending.clear();
        return written;
    }
}
