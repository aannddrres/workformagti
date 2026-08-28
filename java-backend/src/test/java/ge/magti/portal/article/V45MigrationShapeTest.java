package ge.magti.portal.article;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class V45MigrationShapeTest {

    @Test
    void maintainsBoundedReadTimeForEveryArticleWriter() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V45__article_list_read_time_projection.sql"))
                .toLowerCase();

        assertTrue(sql.contains("read_time number(10) default 1 not null"));
        assertTrue(sql.contains("check (read_time >= 1)"));
        assertTrue(sql.contains("regexp_count(content, '[^[:space:]]+')"));
        assertTrue(sql.contains("user_tab_columns"));
        assertTrue(sql.contains("before insert or update on articles"));
        assertTrue(sql.contains("regexp_count(:new.content, '[^[:space:]]+')"));
    }
}
