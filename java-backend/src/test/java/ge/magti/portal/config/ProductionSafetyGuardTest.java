package ge.magti.portal.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyGuardTest {

	/**
	 * Long, varied, and not a placeholder — what a real
	 * {@code secrets.token_urlsafe(64)} looks like. The previous fixture here
	 * was "a-real-generated-secret", which is 23 characters and would now be
	 * rejected on length; that it used to pass is the point of SEC-07.
	 */
	private static final String STRONG_SECRET = "kJ8xQm2vTpZr7Nb4WgYc6HdLf9Es3AuKi1OjRt5Xn0PqMz8Vw2Yb7Gc4Hd6Lf1Ea";

	private static final String REAL_DB_PASSWORD = "Qp7#zR2vLm9!Ks4T";

	private static PortalProperties propertiesWith(String appEnv, String jwtSecret, boolean cookieSecure) {
		PortalProperties properties = new PortalProperties();
		properties.setAppEnv(appEnv);
		properties.getSecurity().getJwt().setSecret(jwtSecret);
		properties.getSecurity().getCookie().setSecure(cookieSecure);
		return properties;
	}

	private static ProductionSafetyGuard guard(PortalProperties properties) {
		return new ProductionSafetyGuard(properties, REAL_DB_PASSWORD);
	}

	@Test
	void devEnvironmentIsNeverChecked() {
		PortalProperties properties = propertiesWith(
				"development", "super-secret-temporary-key-for-local-development", false);
		assertDoesNotThrow(() -> guard(properties).verify());
	}

	/**
	 * SEC-01 acceptance: an unconfigured PortalProperties must be production,
	 * which is what makes every check below apply by default rather than only
	 * when someone remembers to set APP_ENV.
	 */
	@Test
	void defaultEnvironmentIsProduction() {
		assertTrue(new PortalProperties().isProduction());
	}

	@Test
	void productionWithDevSecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "super-secret-temporary-key-for-local-development", true);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	/**
	 * SEC-07: the guard compared against one exact literal, so the placeholder
	 * this repo actually ships in .env.example passed straight through.
	 */
	@Test
	void productionWithShippedPlaceholderSecretFailsLoud() {
		PortalProperties properties = propertiesWith("production", "change-me-to-a-long-random-value", true);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("placeholder"), ex.getMessage());
	}

	@Test
	void productionWithShortSecretFailsLoud() {
		PortalProperties properties = propertiesWith("production", "Qp7zR2vLm9Ks4TxW", true);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("characters"), ex.getMessage());
	}

	/** Long enough to pass the length rule, but almost no entropy. */
	@Test
	void productionWithRepetitiveSecretFailsLoud() {
		PortalProperties properties = propertiesWith("production", "abababababababababababababababababababababababababab", true);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("distinct characters"), ex.getMessage());
	}

	/** PR-05: application.yml shipped a real-looking corporate password as the default. */
	@Test
	void productionWithShippedDevDatabasePasswordFailsLoud() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		ProductionSafetyGuard withDevDbPassword =
				new ProductionSafetyGuard(properties, "MagtiAppDev2026Pw");
		IllegalStateException ex = assertThrows(IllegalStateException.class, withDevDbPassword::verify);
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"), ex.getMessage());
	}

	@Test
	void productionWithPlaceholderDatabasePasswordFailsLoud() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		ProductionSafetyGuard withPlaceholder =
				new ProductionSafetyGuard(properties, "CHANGE_ME_LOCAL_DEV_ONLY");
		IllegalStateException ex = assertThrows(IllegalStateException.class, withPlaceholder::verify);
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"), ex.getMessage());
	}

	/** SEC-01: the bypass must not be able to coexist with a production environment. */
	@Test
	void productionWithDevLoginEnabledFailsLoud() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		properties.getSecurity().setAllowDevLogin(true);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("allow-dev-login"), ex.getMessage());
	}

	@Test
	void productionWithInsecureCookieFailsLoud() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, false);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("COOKIE_SECURE"));
	}

	@Test
	void productionWithRealSecretAndSecureCookiePasses() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		assertDoesNotThrow(() -> guard(properties).verify());
	}

	/**
	 * An absent database password is not the guard's business: Hikari reports
	 * that clearly on the first connection, and treating blank as "dev
	 * default" would block a deployment that supplies credentials another way.
	 */
	@Test
	void productionWithBlankDatabasePasswordIsNotTheGuardsProblem() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		assertDoesNotThrow(() -> new ProductionSafetyGuard(properties, "").verify());
	}

	/**
	 * RTA-021. CSRF tokens are off application-wide and the auth cookie is
	 * accepted on every endpoint, so SameSite is the only reason a cross-site
	 * POST does not act as the logged-in user. "None" removes it silently --
	 * nothing else in the stack changes, and nothing logs an objection.
	 */
	@Test
	void productionWithSameSiteNoneFailsLoud() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		properties.getSecurity().getCookie().setSameSite("None");
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("COOKIE_SAMESITE"));
	}

	/** Header values are case-insensitive, and so is the way people write them. */
	@Test
	void sameSiteNoneIsRejectedWhateverTheCasingAndSpacing() {
		for (String value : new String[] {"none", "NONE", " None "}) {
			PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
			properties.getSecurity().getCookie().setSameSite(value);
			assertThrows(
					IllegalStateException.class,
					() -> guard(properties).verify(),
					"expected SameSite=" + value + " to be refused");
		}
	}

	@Test
	void productionAcceptsLaxAndStrict() {
		for (String value : new String[] {"lax", "Lax", "Strict"}) {
			PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
			properties.getSecurity().getCookie().setSameSite(value);
			assertDoesNotThrow(() -> guard(properties).verify(), "expected SameSite=" + value + " to boot");
		}
	}

	/** The shipped default must be one the guard accepts, or production cannot boot unconfigured. */
	@Test
	void defaultSameSitePassesTheGuard() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		assertDoesNotThrow(() -> guard(properties).verify());
		assertTrue("lax".equalsIgnoreCase(new PortalProperties().getSecurity().getCookie().getSameSite()));
	}

	/**
	 * A non-production deployment may legitimately need SameSite=None while
	 * someone works on a split-origin setup. The guard is a production gate,
	 * not a style rule.
	 */
	@Test
	void developmentMaySetSameSiteNone() {
		PortalProperties properties = propertiesWith("development", STRONG_SECRET, false);
		properties.getSecurity().getCookie().setSameSite("None");
		assertDoesNotThrow(() -> guard(properties).verify());
	}
}
