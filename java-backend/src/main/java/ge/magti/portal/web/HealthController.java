package ge.magti.portal.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First real endpoint of the port, matching GET /api/health's response
 * shape (routers/platform.py:284-...) so the two are directly comparable
 * once the Java side actually has a database and a broker to check.
 *
 * <p>Deliberately does NOT fake "ok" for database/redis -- there is
 * neither yet (see PortalBackendApplication's DataSource exclusion and
 * SecurityConfig's comment). Reporting "not_configured" honestly here is
 * more useful than a green status that means nothing.
 */
@RestController
public class HealthController {

	@GetMapping("/api/health")
	public Map<String, String> health() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("status", "ok");
		body.put("database", "not_configured");
		body.put("redis", "not_configured");
		return body;
	}
}
