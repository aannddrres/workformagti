package ge.magti.portal.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Refuses to boot a production deployment that is configured insecurely, and
 * says so loudly in every other environment.
 *
 * <p>Java equivalent of config.py's startup guard (config.py:117-130),
 * strengthened during the OPUS5 audit follow-up (SEC-01, SEC-07, PR-05):
 *
 * <ul>
 *   <li>the JWT secret check compared against ONE exact literal, so the
 *       placeholder this repo actually ships in .env.example
 *       ("change-me-to-a-long-random-value") sailed straight through;
 *   <li>the database password was not checked at all, while application.yml
 *       shipped a real-looking corporate default;
 *   <li>nothing stopped the password-less dev login from being enabled in a
 *       production environment;
 *   <li>and when the environment was not production the guard returned
 *       immediately, so the one component that could have warned about the
 *       dev bypass said nothing at all.
 * </ul>
 */
@Component
public class ProductionSafetyGuard {

	private static final Logger logger = LoggerFactory.getLogger(ProductionSafetyGuard.class);

	/**
	 * A secret has to be long enough that brute-forcing the HMAC key is not a
	 * realistic path to forging tokens. 48 characters is comfortably past that
	 * for HS256 and still shorter than anything `secrets.token_urlsafe(64)`
	 * produces, which is what the error message tells the operator to run.
	 */
	private static final int MIN_SECRET_LENGTH = 48;

	/**
	 * Distinct-character floor. Catches the other way people satisfy a length
	 * rule — "aaaaaaaa...", "xxxxxxxx..." or a short word repeated — which is
	 * long but carries almost no entropy.
	 */
	private static final int MIN_DISTINCT_CHARS = 12;

	/** Matched as substrings, case-insensitively, against the configured value. */
	private static final List<String> PLACEHOLDER_MARKERS = List.of(
			"change-me", "change_me", "changeme",
			"super-secret-temporary-key", "your-secret", "replace-me", "placeholder",
			"example", "todo", "xxxxx");

	/** Shipped dev defaults. Exact matches, so a real password is never rejected by accident. */
	private static final List<String> KNOWN_DEV_DB_PASSWORDS = List.of(
			"MagtiAppDev2026Pw", "CHANGE_ME_LOCAL_DEV_ONLY", "magti", "oracle", "password");

	private final PortalProperties properties;
	private final String datasourcePassword;

	public ProductionSafetyGuard(
			PortalProperties properties,
			@Value("${spring.datasource.password:}") String datasourcePassword) {
		this.properties = properties;
		this.datasourcePassword = datasourcePassword;
	}

	@PostConstruct
	void verify() {
		logEffectiveSecurityConfig();

		if (!properties.isProduction()) {
			return;
		}

		if (properties.getSecurity().isAllowDevLogin()) {
			throw new IllegalStateException(
					"portal.security.allow-dev-login (ALLOW_DEV_LOGIN) is true with "
							+ "portal.app-env=production -- this enables the password-less dev login, "
							+ "which hands out a SYSTEM_ADMIN token to anyone who can reach the URL. "
							+ "Unset it, or set APP_ENV to something other than production.");
		}

		String secret = properties.getSecurity().getJwt().getSecret();
		rejectWeakSecret(secret);

		if (isKnownDevDatabasePassword(datasourcePassword)) {
			throw new IllegalStateException(
					"spring.datasource.password (ORACLE_DB_PASSWORD) is still a shipped development "
							+ "default with portal.app-env=production. Set a real password.");
		}

		if (!properties.getSecurity().getCookie().isSecure()) {
			throw new IllegalStateException(
					"portal.security.cookie.secure (COOKIE_SECURE) is false with "
							+ "portal.app-env=production -- the auth cookie would be sent over plain HTTP. "
							+ "Set COOKIE_SECURE=true (requires HTTPS).");
		}

		if (!"strict".equalsIgnoreCase(properties.getSecurity().getCookie().getSameSite())) {
			throw new IllegalStateException(
					"portal.security.cookie.same-site (COOKIE_SAMESITE) must be strict in production "
							+ "because the portal uses an httpOnly authentication cookie.");
		}

		verifyCorporateLogin(properties.getSecurity().getCorporate());

		long idleMinutes = properties.getSecurity().getSession().getIdleMinutes();
		long maximumMinutes = properties.getSecurity().getSession().getMaximumMinutes();
		if (idleMinutes <= 0 || maximumMinutes <= 0 || idleMinutes > maximumMinutes) {
			throw new IllegalStateException(
					"portal.security.session must have positive idle/maximum values and idle must not exceed maximum");
		}
		if (properties.getSecurity().getJwt().getAccessTokenExpireMinutes() > maximumMinutes) {
			throw new IllegalStateException(
					"JWT lifetime must not exceed the portal session maximum lifetime");
		}
	}

	/**
	 * Production signs people in only through the company directory (PO-25),
	 * so a half-configured login is not a degraded mode -- it is a portal
	 * nobody can enter, or one that sends passwords somewhere it should not.
	 *
	 * <p>Switched off, the portal boots and says loudly that it admits nobody:
	 * that is a legitimate state while IT finishes its side. Switched on, every
	 * value it needs must be real, the endpoint must be HTTPS (the password
	 * travels in the body), and the role map must parse -- a typo there would
	 * otherwise demote every administrator to operator at their next sign-in.
	 */
	private static void verifyCorporateLogin(PortalProperties.Corporate corporate) {
		if (!corporate.isEnabled()) {
			logger.warn("SECURITY: company login (CORPORATE_AUTH_ENABLED) is off with portal.app-env=production -- "
					+ "there is no local-password fallback (PO-25), so nobody can sign in.");
			return;
		}
		String uri = corporate.getServiceUri();
		if (uri == null || uri.isBlank() || !uri.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
			throw new IllegalStateException(
					"OAUTH_SERVICE_URI must be an https:// address with CORPORATE_AUTH_ENABLED=true in production -- "
							+ "the employee's password travels in the request body.");
		}
		if (corporate.getClientId() == null || corporate.getClientId().isBlank()) {
			throw new IllegalStateException("OAUTH_CLIENT_ID is not set with CORPORATE_AUTH_ENABLED=true.");
		}
		String credential = corporate.getClientCredential();
		if (credential == null || credential.isBlank() || containsPlaceholderMarker(credential)) {
			throw new IllegalStateException(
					"OAUTH_SECRET is not set, or is still a placeholder, with CORPORATE_AUTH_ENABLED=true.");
		}
		try {
			ge.magti.portal.security.DirectoryRoleMapper.validate(corporate.getRoleMap());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("OAUTH_ROLE_MAP is invalid: " + e.getMessage());
		}
	}

	private void rejectWeakSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw fail("is not set");
		}
		if (containsPlaceholderMarker(secret)) {
			throw fail("is still a placeholder value");
		}
		if (secret.length() < MIN_SECRET_LENGTH) {
			throw fail("is only " + secret.length() + " characters (minimum " + MIN_SECRET_LENGTH + ")");
		}
		if (secret.chars().distinct().count() < MIN_DISTINCT_CHARS) {
			throw fail("has too few distinct characters to be a real random value");
		}
	}

	private static boolean containsPlaceholderMarker(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
	}

	private static boolean isKnownDevDatabasePassword(String password) {
		if (password == null || password.isBlank()) {
			return false; // absent is a different failure, and Hikari reports it clearly
		}
		return KNOWN_DEV_DB_PASSWORDS.stream().anyMatch(password::equals)
				|| containsPlaceholderMarker(password);
	}

	private static IllegalStateException fail(String problem) {
		return new IllegalStateException(
				"portal.security.jwt.secret (SECRET_KEY) " + problem
						+ " with portal.app-env=production. Generate one: "
						+ "python -c \"import secrets; print(secrets.token_urlsafe(64))\"");
	}

	/**
	 * One line, on every boot, in every environment (audit PR-08).
	 *
	 * <p>The dev bypass used to be completely silent: anyone looking at a
	 * running instance had no way to tell whether password-less login was
	 * live short of trying it, which is precisely how SEC-01 could have
	 * reached production unnoticed. The first fix logged it -- but only in
	 * the non-production branch, because the production path returned after
	 * its checks. That left the one environment where the line matters most
	 * as the one that never printed it.
	 *
	 * <p>Now it always prints, before any check can throw, so even a boot
	 * that ProductionSafetyGuard aborts leaves a record of the configuration
	 * that caused it. trusted-proxies is included because an empty value
	 * silently changes what the login rate limiter and the audit log see
	 * (SEC-04/PR-04) -- exactly the class of "quietly not what you think"
	 * this line exists to expose.
	 */
	private void logEffectiveSecurityConfig() {
		int trustedProxyCount = properties.getSecurity().getTrustedProxies().size();
		logger.info(
				"Startup security config: app-env={}, dev-login={}, cookie-secure={}, cookie-samesite={}, "
						+ "trusted-proxies={}, company-login={}",
				properties.getAppEnv(),
				properties.getSecurity().isAllowDevLogin() ? "ENABLED" : "disabled",
				properties.getSecurity().getCookie().isSecure(),
				properties.getSecurity().getCookie().getSameSite(),
				trustedProxyCount == 0 ? "none (X-Forwarded-For ignored)" : trustedProxyCount + " configured",
				properties.getSecurity().getCorporate().isEnabled() ? "ENABLED" : "disabled");

		if (properties.getSecurity().isAllowDevLogin()) {
			logger.warn(
					"SECURITY: password-less dev login is ENABLED (portal.app-env={}, "
							+ "portal.security.allow-dev-login=true). Any password is accepted for the "
							+ "seeded test accounts, including an admin. This must never be a "
							+ "production or shared environment.",
					properties.getAppEnv());
		}
	}
}
