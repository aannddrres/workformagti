package ge.magti.portal.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Java equivalent of config.py's startup guard (config.py:117-130) --
 * flagged as a "todo" in application.yml's jwt.secret comment since Phase
 * 1d, closed now during the 2026-08-11 PM migration-gap audit's
 * production-readiness pass. Fails loud at boot rather than silently
 * shipping a dev secret or a cookie sent over plain HTTP if {@code
 * portal.app-env=production} — same two checks, same reasoning, as the
 * Python original.
 */
@Component
public class ProductionSafetyGuard {

	private static final String DEV_JWT_SECRET = "super-secret-temporary-key-for-local-development";

	private final PortalProperties properties;

	public ProductionSafetyGuard(PortalProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void verify() {
		if (!properties.isProduction()) {
			return;
		}
		if (DEV_JWT_SECRET.equals(properties.getSecurity().getJwt().getSecret())) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) is still the development default "
							+ "with portal.app-env=production. Generate one: "
							+ "python -c \"import secrets; print(secrets.token_urlsafe(64))\"");
		}
		if (!properties.getSecurity().getCookie().isSecure()) {
			throw new IllegalStateException(
					"portal.security.cookie.secure (COOKIE_SECURE) is false with "
							+ "portal.app-env=production -- the auth cookie would be sent over plain HTTP. "
							+ "Set COOKIE_SECURE=true (requires HTTPS).");
		}
	}
}
