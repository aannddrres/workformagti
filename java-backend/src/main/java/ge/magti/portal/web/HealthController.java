package ge.magti.portal.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health, without Redis/multi-worker checks -- no broker exists on the Java
 * side yet, so "redis" stays honestly "not_configured" rather than faking
 * a status for infrastructure that isn't there.
 *
 * <p>The database check is real now (Phase 1b gave this a live Oracle
 * DataSource) -- a bare {@code SELECT 1} dependency/readiness
 * probe, not a guess. Process liveness is exposed
 * separately by Actuator and deliberately does not depend on Oracle.
 *
 * <p><b>PR-09:</b> this endpoint is unauthenticated, and the failure branch
 * used to return {@code "error: " + e.getMessage()} straight to the caller.
 * An Oracle connection failure's message carries the JDBC URL -- host, port
 * and service name -- so an outage handed anyone who could reach the URL a
 * map of the internal database. The detail now goes to the log, where the
 * people diagnosing the outage are already looking, and the response says
 * only that the check failed.
 *
 * <p>A failed dependency check returns HTTP 503. Returning a degraded JSON
 * body with HTTP 200 would tell an orchestrator to keep routing traffic to an
 * instance that cannot serve database-backed requests.
 */
@RestController
public class HealthController {

	private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/api/health")
	public ResponseEntity<Map<String, String>> health() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("status", "ok");
		body.put("redis", "not_configured");

		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT 1 FROM dual");
			body.put("database", "ok");
			return ResponseEntity.ok(body);
		} catch (Exception e) {
			// Full detail (including the JDBC URL) to the log, not the wire.
			logger.error("Health check: database probe failed", e);
			body.put("database", "error");
			body.put("status", "degraded");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
		}
	}
}
