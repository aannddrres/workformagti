package ge.magti.portal.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleHistoryRepositoryCustomImplTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bindsUpdatedAtAsOffsetDateTimeInsteadOfDroppingItsOffset() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-20T12:30:00+04:00");
        ArticleHistoryRepositoryCustomImpl repository = new ArticleHistoryRepositoryCustomImpl(jdbcTemplate);

        repository.archiveIfMissing(11L, "სათაური", "შინაარსი", 22L, 3, updatedAt);

        verify(statement).setObject(6, updatedAt);
        verify(statement, never()).setTimestamp(eq(6), any(Timestamp.class));
    }
}
