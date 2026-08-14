package ge.magti.portal.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Java equivalent of config.py's startup guard (config.py:117-130) --
 * flagged as a "todo" in application.yml's jwt.secret comment since Phase
 * 1d, closed during the 2026-08-11 PM migration-gap audit's
 * production-readiness pass. Fails loud at boot rather than silently
 * shipping a dev secret or a cookie sent over plain HTTP if {@code
 * portal.app-env=production}.
 *
 * <p>Widened after audit OPUS5 (SEC-01, SEC-07, PR-05), which found the
 * original three-line version too easy to satisfy. With {@code
 * portal.app-env=production} this now refuses to boot when:
 * <ul>
 *   <li>{@code portal.security.allow-dev-login} is true -- the password-less
 *       bypass may never be combined with production (SEC-01);</li>
 *   <li>the JWT secret is missing, is the dev literal, is shorter than
 *       {@value #MIN_SECRET_LENGTH} characters, looks like one of the
 *       placeholders this repo ships, or has fewer than
 *       {@value #MIN_DISTINCT_SECRET_CHARS} distinct characters (SEC-07 --
 *       the old check compared against ONE literal, so
 *       .env.example's "change-me-to-a-long-random-value" passed);</li>
 *   <li>the auth cookie is not {@code secure};</li>
 *   <li>the Oracle password is missing, is the dev default that used to be
 *       committed in application.yml, or is a placeholder (PR-05).</li>
 * </ul>
 *
 * <p>What it deliberately does NOT check: that the secret is actually random
 * (a 48-character run of prose passes the distinct-character test), that the
 * DB user has least-privilege grants, or anything about TLS termination in
 * front of the app. It is a footgun guard, not an audit.
 *
 * <p>Outside production it throws nothing, but it does WARN -- before OPUS5
 * the non-production path returned silently at line 1, which is precisely
 * why a dev-mode deployment could look healthy while accepting any password
 * for admin@magti.ge.
 */
@Component
public class ProductionSafetyGuard {

	private static final Logger log = LoggerFactory.getLogger(ProductionSafetyGuard.class);

	private static final String DEV_JWT_SECRET = "super-secret-temporary-key-for-local-development";

	/**
	 * Passwords that must never reach production: the dev default that
	 * application.yml:15 used to ship (it is in this repo's git history
	 * forever, see PR-05) and .env.example's placeholder.
	 */
	private static final List<String> KNOWN_DEV_DB_PASSWORDS = List.of(
			"MagtiAppDev2026Pw", "CHANGE_ME_LOCAL_ONLY");

	private static final int MIN_SECRET_LENGTH = 48;

	private static final int MIN_DISTINCT_SECRET_CHARS = 10;

	/** Lower-cased fragments that mark a value as "someone forgot to replace this". */
	private static final List<String> PLACEHOLDER_MARKERS = List.of(
			"change-me", "change_me", "changeme", "replace-me", "replace_me", "replaceme",
			"placeholder", "your-secret", "your_secret", "temporary", "local-only", "local_only",
			"example", "todo", "xxxxx");

	private final PortalProperties properties;

	private final String databasePassword;

	public ProductionSafetyGuard(
			PortalProperties properties,
			@Value("${spring.datasource.password:}") String databasePassword) {
		this.properties = properties;
		this.databasePassword = databasePassword;
	}

	@PostConstruct
	void verify() {
		boolean devLoginRequested = properties.getSecurity().isAllowDevLogin();

		if (!properties.isProduction()) {
			log.warn("portal.app-env={} -- this is NOT a production configuration. "
					+ "Set APP_ENV=production for any real deployment.", properties.getAppEnv());
			if (devLoginRequested) {
				log.warn("SECURITY: password-less dev login is ENABLED "
						+ "(portal.security.allow-dev-login=true). admin@magti.ge, content@magti.ge, "
						+ "manager@magti.ge, nino@/tech@/info@magti.ge and any test_operator_* address "
						+ "are accepted with ANY password, and admin@magti.ge is provisioned as "
						+ "SYSTEM_ADMIN. Local development only -- never set ALLOW_DEV_LOGIN outside it.");
			}
			return;
		}

		if (devLoginRequested) {
			throw new IllegalStateException(
					"portal.security.allow-dev-login (ALLOW_DEV_LOGIN) is true with "
							+ "portal.app-env=production -- that would accept ANY password for "
							+ "admin@magti.ge and hand out a SYSTEM_ADMIN token. Unset ALLOW_DEV_LOGIN.");
		}

		verifyJwtSecret(properties.getSecurity().getJwt().getSecret());

		if (!properties.getSecurity().getCookie().isSecure()) {
			throw new IllegalStateException(
					"portal.security.cookie.secure (COOKIE_SECURE) is false with "
							+ "portal.app-env=production -- the auth cookie would be sent over plain HTTP. "
							+ "Set COOKIE_SECURE=true (requires HTTPS).");
		}

		verifyDatabasePassword(databasePassword);

		log.info("Production safety checks passed: app-env=production, dev-login disabled, "
				+ "cookie secure=true, JWT secret and DB password are not shipped defaults.");
	}

	private void verifyJwtSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) is not set with "
							+ "portal.app-env=production. " + generateHint());
		}
		if (DEV_JWT_SECRET.equals(secret)) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) is still the development default "
							+ "with portal.app-env=production. " + generateHint());
		}
		if (secret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) is only " + secret.length()
							+ " characters with portal.app-env=production -- at least "
							+ MIN_SECRET_LENGTH + " are required. " + generateHint());
		}
		if (containsPlaceholderMarker(secret)) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) still looks like a placeholder "
							+ "(e.g. .env.example's \"change-me-to-a-long-random-value\") with "
							+ "portal.app-env=production. " + generateHint());
		}
		if (distinctCharacterCount(secret) < MIN_DISTINCT_SECRET_CHARS) {
			throw new IllegalStateException(
					"portal.security.jwt.secret (SECRET_KEY) uses only "
							+ distinctCharacterCount(secret) + " distinct characters with "
							+ "portal.app-env=production -- that is not a random secret. "
							+ generateHint());
		}
	}

	private void verifyDatabasePassword(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalStateException(
					"spring.datasource.password (ORACLE_DB_PASSWORD) is not set with "
							+ "portal.app-env=production.");
		}
		if (KNOWN_DEV_DB_PASSWORDS.contains(password)) {
			throw new IllegalStateException(
					"spring.datasource.password (ORACLE_DB_PASSWORD) is a development default that "
							+ "is published in this repository's git history, and portal.app-env=production. "
							+ "Set a real password for the production Oracle account.");
		}
		if (containsPlaceholderMarker(password)) {
			throw new IllegalStateException(
					"spring.datasource.password (ORACLE_DB_PASSWORD) still looks like a placeholder "
							+ "with portal.app-env=production. Set the real password.");
		}
	}

	private static boolean containsPlaceholderMarker(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
	}

	private static long distinctCharacterCount(String value) {
		return value.chars().distinct().count();
	}

	private static String generateHint() {
		return "Generate one: python -c \"import secrets; print(secrets.token_urlsafe(64))\"";
	}
}
