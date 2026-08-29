package ge.magti.portal.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps {@code stored_file_references} in step with what content actually
 * references (DEC-P01, migration V46).
 *
 * <p>The index exists because the authorization path cannot afford the scan
 * the purge path uses: {@code ContentLifecycleService.referenceCount} costs
 * ~70 ms per file against a 123-article corpus and grows with it, which is
 * acceptable once during a purge and not acceptable for every image in every
 * article view.
 *
 * <p><b>Sync is a replace, not a merge.</b> Editing an article can remove an
 * image as easily as add one, and a stale row here would keep a file readable
 * after the page stopped mentioning it -- the exact failure this whole change
 * is about. Delete-then-insert for the item is the only shape that cannot
 * leave one behind.
 *
 * <p>Callers pass the raw text they just saved rather than re-reading it, so
 * the index is written inside the same transaction as the content it
 * describes. A commit that stored new content and old references would be
 * worse than either alone.
 */
@Service
public class FileReferenceIndex {

    private static final Logger logger = LoggerFactory.getLogger(FileReferenceIndex.class);

    private final JdbcTemplate jdbcTemplate;

    public FileReferenceIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Record exactly the files the given texts reference, replacing whatever
     * was recorded for this item before.
     *
     * @param itemType one of {@code article}, {@code news}, {@code video}
     * @param texts    body, attachment URL, video URL -- whatever that item
     *                 type can carry a reference in; nulls are ignored
     */
    @Transactional
    public void sync(String itemType, Long itemId, String... texts) {
        if (itemType == null || itemId == null) {
            return;
        }
        forget(itemType, itemId);
        Set<String> filenames = UploadReferences.in(texts);
        if (filenames.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(filenames.size());
        for (String filename : filenames) {
            rows.add(new Object[] {filename, itemType, itemId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO stored_file_references (filename, item_type, item_id) VALUES (?, ?, ?)",
                rows);
    }

    /** Drop every reference recorded for an item, on purge or hard delete. */
    @Transactional
    public void forget(String itemType, Long itemId) {
        if (itemType == null || itemId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM stored_file_references WHERE item_type = ? AND item_id = ?",
                itemType, itemId);
    }

    /**
     * Which live items reference this file, for the access decision to judge.
     *
     * <p>Trashed content is filtered here, in SQL, rather than by the caller:
     * {@code trashed_at} is a column the JPA entities do not map -- the trash
     * lifecycle is raw JDBC throughout -- so this is the only layer that can
     * see it without inventing a second mapping of the same state.
     *
     * <p>Excluding it matters. Trashing an article does not delete the file it
     * carried; the file waits out its retention window in case the article is
     * restored. Leaving the reference behind would keep the picture readable
     * for exactly as long as the article that justified it was gone.
     */
    public List<Reference> referencesTo(String filename) {
        if (filename == null || filename.isBlank()) {
            return List.of();
        }
        List<Reference> indexed = queryIndex(filename);
        if (!indexed.isEmpty()) {
            return indexed;
        }
        // Nothing indexed. Two very different situations look identical here:
        // a genuinely unreferenced upload (an editor's image, seconds old), and
        // a file whose references were never written because some save path
        // does not maintain the index.
        //
        // Guessing wrong in the second case would blank out pictures in
        // articles that are perfectly fine, and nothing would report it as an
        // error. So fall through to the authoritative scan -- the same one
        // ContentLifecycleService trusts for purge decisions -- and write what
        // it finds back into the index.
        //
        // It costs ~70 ms, and only for files the index does not know. A
        // missed save site therefore degrades to slow-and-correct rather than
        // fast-and-wrong, and heals itself on first access.
        List<Reference> scanned = scanForReferences(filename);
        if (!scanned.isEmpty()) {
            logger.warn("stored_file_references had no row for {} but content references it "
                    + "({} items); healing from scan. A content save path is not syncing.",
                    filename, scanned.size());
            backfill(filename, scanned);
        }
        return scanned;
    }

    private List<Reference> queryIndex(String filename) {
        String sql = """
                SELECT r.item_type, r.item_id
                FROM stored_file_references r
                WHERE r.filename = ?
                  AND (
                        (r.item_type = 'article'
                         AND EXISTS (SELECT 1 FROM articles a
                                     WHERE a.id = r.item_id AND a.trashed_at IS NULL))
                     OR (r.item_type = 'news'
                         AND EXISTS (SELECT 1 FROM news n
                                     WHERE n.id = r.item_id AND n.trashed_at IS NULL))
                     OR (r.item_type = 'video'
                         AND EXISTS (SELECT 1 FROM video_instructions v
                                     WHERE v.id = r.item_id AND v.trashed_at IS NULL))
                  )
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Reference(rs.getString("item_type"), rs.getLong("item_id")),
                filename);
    }

    /**
     * The authoritative answer, at the price of a full scan. Deliberately the
     * same predicate shape ContentLifecycleService.referenceCount uses -- the
     * two must not disagree about what "referenced" means.
     */
    private List<Reference> scanForReferences(String filename) {
        String sql = """
                SELECT 'article' AS item_type, id AS item_id FROM articles
                 WHERE trashed_at IS NULL
                   AND (DBMS_LOB.INSTR(content, ?) > 0 OR INSTR(NVL(attachment_url, ' '), ?) > 0)
                UNION ALL
                SELECT 'news', id FROM news
                 WHERE trashed_at IS NULL
                   AND (DBMS_LOB.INSTR(content, ?) > 0 OR INSTR(NVL(attachment_url, ' '), ?) > 0)
                UNION ALL
                SELECT 'video', id FROM video_instructions
                 WHERE trashed_at IS NULL AND INSTR(NVL(video_url, ' '), ?) > 0
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Reference(rs.getString("item_type"), rs.getLong("item_id")),
                filename, filename, filename, filename, filename);
    }

    @Transactional
    protected void backfill(String filename, List<Reference> references) {
        List<Object[]> rows = new ArrayList<>(references.size());
        for (Reference reference : references) {
            rows.add(new Object[] {filename, reference.itemType(), reference.itemId()});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO stored_file_references (filename, item_type, item_id) VALUES (?, ?, ?)",
                rows);
    }

    public record Reference(String itemType, Long itemId) {
    }
}
