package ge.magti.portal.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.StringReader;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;

/**
 * Binds article content as a real JDBC CLOB instead of a native-query String.
 *
 * <p>The old Spring Data native query bound {@code :content} as VARCHAR2.
 * Oracle rejects ordinary Georgian articles once their UTF-8 representation
 * crosses the roughly 4,000-byte SQL scalar limit (ORA-01461), even though
 * the destination column is a CLOB. {@link PreparedStatement#setClob(int,
 * java.io.Reader, long)} makes the intended type explicit.
 *
 * <p>The anonymous PL/SQL block also keeps the original insert-if-missing
 * contract without leaking ORA-00001 into the surrounding Spring
 * transaction. Catching that exception in Java would be too late: a failed
 * Hibernate flush marks the shared transaction rollback-only. Handling
 * {@code DUP_VAL_ON_INDEX} inside Oracle means a concurrent or repeated
 * archive remains a no-op and the caller can continue updating/restoring the
 * article in the same transaction.
 */
@Repository
public class ArticleHistoryRepositoryCustomImpl implements ArticleHistoryRepositoryCustom {

    private static final String ARCHIVE_IF_MISSING_SQL = """
            BEGIN
                INSERT INTO article_history (article_id, title, content, updated_by, version_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?);
            EXCEPTION
                WHEN DUP_VAL_ON_INDEX THEN NULL;
            END;
            """;

    private final JdbcTemplate jdbcTemplate;

    public ArticleHistoryRepositoryCustomImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void archiveIfMissing(
            Long articleId, String title, String content, Long updatedBy, Integer versionId,
            OffsetDateTime updatedAt) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ARCHIVE_IF_MISSING_SQL)) {
                statement.setLong(1, articleId);
                statement.setString(2, title);
                statement.setClob(3, new StringReader(content), content.length());
                statement.setLong(4, updatedBy);
                if (versionId == null) {
                    statement.setNull(5, java.sql.Types.NUMERIC);
                } else {
                    statement.setInt(5, versionId);
                }
                // Match Hibernate's OffsetDateTime binding for the JPA writers
                // of this same Oracle TIMESTAMP column. Binding the temporal
                // value with its offset lets Oracle normalize it to the
                // connection/session zone exactly as those writers do.
                statement.setObject(6, updatedAt);
                statement.execute();
                return null;
            }
        });
    }
}
