package ge.magti.portal.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RTA-009. The bug was never that the probe failed to notice Oracle was gone
 * -- it noticed, wrote "degraded" into the body, and returned 200 anyway.
 * Nothing reads the body, so every replica stayed in the load balancer.
 *
 * <p>These tests are about status codes for that reason, and the liveness
 * ones matter as much as the readiness ones: a liveness probe that starts
 * failing during a database outage would restart every pod in the cluster and
 * make the outage worse.
 *
 * <p>No Spring context -- the controller's whole job is turning one DataSource
 * answer into one status code, and a mock says that faster and more directly
 * than a running application.
 */
class HealthControllerTest {

	private static DataSource workingDataSource() throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		Statement statement = mock(Statement.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.createStatement()).thenReturn(statement);
		when(statement.execute(anyString())).thenReturn(true);
		return dataSource;
	}

	private static DataSource brokenDataSource() throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		// The real failure mode, and the one PR-09 was about: the message
		// carries the JDBC URL. Asserted below never to reach the response.
		when(dataSource.getConnection())
				.thenThrow(new SQLException("IO Error: connect timed out, jdbc:oracle:thin:@db.internal:1521/XEPDB1"));
		return dataSource;
	}

	// --- readiness: the one that must fail loudly ------------------------

	@Test
	void readinessIsOkWhileTheDatabaseAnswers() throws Exception {
		ResponseEntity<Map<String, String>> response = new HealthController(workingDataSource()).readiness();
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("ready", response.getBody().get("status"));
	}

	@Test
	void readinessReturns503WhenTheDatabaseIsGone() throws Exception {
		ResponseEntity<Map<String, String>> response = new HealthController(brokenDataSource()).readiness();
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
				"a pod that cannot reach Oracle must leave the load balancer");
		assertEquals("not_ready", response.getBody().get("status"));
	}

	// --- liveness: the one that must NOT ---------------------------------

	@Test
	void livenessStaysHealthyWhenTheDatabaseIsGone() throws Exception {
		// Restarting the application will not bring Oracle back. If this ever
		// starts failing on a database outage, every replica enters a crash
		// loop and recovery takes longer than the outage.
		Map<String, String> body = new HealthController(brokenDataSource()).health();
		assertEquals("degraded", body.get("status"), "the body still tells an operator what is wrong");
		assertEquals("error", body.get("database"));
	}

	@Test
	void livenessReportsOkWhenEverythingWorks() throws Exception {
		Map<String, String> body = new HealthController(workingDataSource()).health();
		assertEquals("ok", body.get("status"));
		assertEquals("ok", body.get("database"));
		assertEquals("not_configured", body.get("redis"));
	}

	// --- PR-09: no internal topology on the wire -------------------------

	@Test
	void neitherEndpointLeaksTheJdbcUrl() throws Exception {
		String liveness = String.valueOf(new HealthController(brokenDataSource()).health());
		String readiness = String.valueOf(new HealthController(brokenDataSource()).readiness().getBody());
		for (String rendered : new String[] {liveness, readiness}) {
			assertEquals(false, rendered.contains("jdbc:"), rendered);
			assertEquals(false, rendered.contains("db.internal"), rendered);
			assertEquals(false, rendered.contains("1521"), rendered);
		}
	}
}
