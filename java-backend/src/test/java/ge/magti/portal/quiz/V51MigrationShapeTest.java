package ge.magti.portal.quiz;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two choices in V51 that look like mistakes and are not: the key uses
 * article_id_snapshot (article_id is nulled by a purge, V42), and the
 * constraint does not validate history (old duplicates are evidence).
 */
class V51MigrationShapeTest {

    @Test
    void keysOnTheSnapshotAndLeavesHistoryAsItIs() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V51__quiz_attempt_number_unique.sql"))
                .toLowerCase()
                .replaceAll("--[^\\n]*", "");

        assertTrue(sql.contains("unique (user_id, article_id_snapshot, article_version, attempt_number)"));
        assertTrue(sql.contains("enable novalidate"));
        assertTrue(sql.contains("using index ix_quiz_attempts_attempt_no"));
        assertFalse(sql.contains("create unique index"), "a unique index would not build over old duplicates");
    }
}
