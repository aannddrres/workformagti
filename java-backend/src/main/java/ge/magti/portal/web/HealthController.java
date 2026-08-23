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
 * Matches GET /api/health's response shape (routers/platform.py:284-311),
 * minus the Redis/multi-worker checks -- no broker exists on the Java
 * side yet, so "redis" stays honestly "not_configured" rather than faking
 * a status for infrastructure that isn't there.
 *
 * <p><b>PR-09:</b> these endpoints are unauthenticated, and the failure branch
 * used to return {@code "error: " + e.getMessage()} straight to the caller.
 * An Oracle connection failure's message carries the JDBC URL -- host, port
 * and service name -- so an outage handed anyone who could reach the URL a
 * map of the internal database. The detail now goes to the log, where the
 * people diagnosing the outage are already looking, and the response says
 * only that the check failed.
 *
 * <p><b>RTA-009: liveness and readiness are different questions, and answering
 * them with one endpoint answered neither.</b> {@code /api/health} used to
 * probe the database and, on failure, return HTTP <b>200</b> with
 * {@code "status": "degraded"} in the body. Nothing reads the body: the image
 * healthcheck is {@code wget ... || exit 1}, and a Kubernetes probe looks at
 * the status line. So every replica stayed "healthy" and kept receiving user
 * requests it could not serve, while the orchestrator reported the deployment
 * green.
 *
 * <p>Splitting them is not cosmetic, and returning 503 from the wrong one
 * would have been worse than the bug:
 *
 * <ul>
 *   <li><b>{@link #health() liveness}</b> answers "is this JVM still working?"
 *       It deliberately does <b>not</b> touch the database. A failing
 *       liveness probe means <i>restart this container</i> -- and restarting
 *       an application because Oracle is down turns a database outage into a
 *       cluster-wide crash loop that takes longer to recover than the outage
 *       itself. This is what the image {@code HEALTHCHECK} calls.
 *   <li><b>{@link #readiness() readiness}</b> answers "can this instance serve
 *       a request right now?" It probes the database and returns <b>503</b>
 *       when it cannot. A failing readiness probe means <i>stop sending this
 *       pod traffic</i>, leaving it running and ready to rejoin the moment
 *       the database returns.
 * </ul>
 *
 * <p>Configure the Kubernetes probes accordingly: {@code livenessProbe} on
 * {@code /api/health}, {@code readinessProbe} on {@code /api/health/ready}.
 * Documented here rather than only in a manifest, because no manifest exists
 * in this repository yet (RTA-001, IT question 4).
 */
@RestController
public class HealthController {

	private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Liveness. Always 200 while the application can answer at all.
	 *
	 * <p>The body still reports the database, because the response shape is a
	 * port of the Python endpoint and operators read it by hand during an
	 * incident. But the <b>status code</b> no longer depends on it -- that is
	 * {@link #readiness()}'s job.
	 */
	@GetMapping("/api/health")
	public Map<String, String> health() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("status", "ok");
		body.put("redis", "not_configured");
		boolean databaseUp = probeDatabase();
		body.put("database", databaseUp ? "ok" : "error");
		if (!databaseUp) {
			body.put("status", "degraded");
		}
		return body;
	}

	/**
	 * Readiness. 200 when this instance can serve requests, 503 when it cannot.
	 *
	 * <p>Unauthenticated like {@link #health()}, and for the same reason: a
	 * probe runs before any session exists. It reveals nothing an anonymous
	 * caller could not learn by watching the service fail.
	 */
	@GetMapping("/api/health/ready")
	public ResponseEntity<Map<String, String>> readiness() {
		Map<String, String> body = new LinkedHashMap<>();
		if (probeDatabase()) {
			body.put("status", "ready");
			body.put("database", "ok");
			return ResponseEntity.ok(body);
		}
		body.put("status", "not_ready");
		body.put("database", "error");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
	}

	/** The same bare {@code SELECT 1} the Python original ran -- not a guess at liveness. */
	private boolean probeDatabase() {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT 1 FROM dual");
			return true;
		} catch (Exception e) {
			// Full detail (including the JDBC URL) to the log, not the wire.
			logger.error("Health check: database probe failed", e);
			return false;
		}
	}
}
