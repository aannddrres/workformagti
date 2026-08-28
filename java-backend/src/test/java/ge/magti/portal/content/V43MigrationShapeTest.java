package ge.magti.portal.content;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V43MigrationShapeTest {

    @Test
    void removesFeedbackPayloadAndKeepsOnlyCountAndTimestamp() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V43__remove_knowledge_feedback_data.sql")) {
            assert stream != null;
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("count(*)"));
        assertTrue(sql.contains("delete from knowledge_feedback where 1 = 1"));
        assertTrue(sql.contains("drop table knowledge_feedback"));
        assertTrue(sql.contains("data_migration_audit"));
        assertFalse(sql.contains("feedback_text"));
        assertFalse(sql.contains("create table knowledge_feedback_backup"));
    }
}
