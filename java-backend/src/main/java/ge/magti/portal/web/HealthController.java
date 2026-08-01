package ge.magti.portal.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Matches GET /api/health's response shape (routers/platform.py:284-311),
 * minus the Redis/multi-worker checks -- no broker exists on the Java
 * side yet, so "redis" stays honestly "not_configured" rather than faking
 * a status for infrastructure that isn't there.
 *
 * <p>The database check is real now (Phase 1b gave this a live Oracle
 * DataSource) -- runs the same bare {@code SELECT 1} liveness probe as
 * the Python original, not a guess.
 */
@RestController
public class HealthController {

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/api/health")
	public Map<String, String> health() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("status", "ok");
		body.put("redis", "not_configured");

		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT 1 FROM dual");
			body.put("database", "ok");
		} catch (Exception e) {
			body.put("database", "error: " + e.getMessage());
			body.put("status", "degraded");
		}

		return body;
	}
}
