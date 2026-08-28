package ge.magti.portal.web;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void healthyDatabaseReturnsOkWithoutInternalDetails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT 1 FROM dual")).thenReturn(true);

        var response = new HealthController(dataSource).health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().get("status"));
        assertEquals("ok", response.getBody().get("database"));
        assertFalse(response.getBody().containsKey("detail"));
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void databaseFailureReturns503AndDoesNotLeakJdbcError() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLException("connection refused at internal-db.example.invalid:1521/secret-service"));

        var response = new HealthController(dataSource).health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("degraded", response.getBody().get("status"));
        assertEquals("error", response.getBody().get("database"));
        assertFalse(response.getBody().toString().contains("internal-db"));
        assertFalse(response.getBody().toString().contains("secret-service"));
    }
}
