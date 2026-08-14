package ge.magti.portal.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyGuardTest {

	/**
	 * 64 characters, mixed alphabet -- long enough and varied enough to pass
	 * every check, so a test that fails does so for the reason it names.
	 */
	private static final String REAL_SECRET = "Xq7fLp2VtRn9WzKd4HsYbA6MjCe1UgTv8QoZxNr3PwLiEy5BkDaS0FcHmJuOtIvR";

	private static final String REAL_DB_PASSWORD = "8Kq2vRt7WnZp4Lx9";

	private static PortalProperties propertiesWith(String appEnv, String jwtSecret, boolean cookieSecure) {
		PortalProperties properties = new PortalProperties();
		properties.setAppEnv(appEnv);
		properties.getSecurity().getJwt().setSecret(jwtSecret);
		properties.getSecurity().getCookie().setSecure(cookieSecure);
		return properties;
	}

	private static ProductionSafetyGuard guardFor(PortalProperties properties) {
		return new ProductionSafetyGuard(properties, REAL_DB_PASSWORD);
	}

	@Test
	void devEnvironmentIsNeverChecked() {
		PortalProperties properties = propertiesWith(
				"development", "super-secret-temporary-key-for-local-development", false);
		assertDoesNotThrow(() -> guardFor(properties).verify());
	}

	@Test
	void productionWithDevSecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "super-secret-temporary-key-for-local-development", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	@Test
	void productionWithInsecureCookieFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, false);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("COOKIE_SECURE"));
	}

	@Test
	void productionWithRealSecretAndSecureCookiePasses() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		assertDoesNotThrow(() -> guardFor(properties).verify());
	}

	// --- audit OPUS5 SEC-07: the guard used to compare against ONE literal ---

	/**
	 * The exact string java-backend/.env.example ships. Before the fix it was
	 * "not the dev literal", therefore accepted -- while being published in
	 * this repository, so anyone could forge an admin token.
	 */
	@Test
	void productionWithTheEnvExamplePlaceholderSecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "change-me-to-a-long-random-value", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	@Test
	void productionWithAnUppercasePlaceholderSecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "CHANGE_ME_TO_SOMETHING_LONG_AND_RANDOM_BEFORE_DEPLOY", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	@Test
	void productionWithAShortSecretFailsLoud() {
		PortalProperties properties = propertiesWith("production", "a-real-generated-secret", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
		assertTrue(ex.getMessage().contains("48"));
	}

	@Test
	void productionWithABlankSecretFailsLoud() {
		PortalProperties properties = propertiesWith("production", "   ", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	/** Long enough, no placeholder marker -- but three distinct characters. */
	@Test
	void productionWithALowEntropySecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "abcabcabcabcabcabcabcabcabcabcabcabcabcabcabcabcabc", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	// --- audit OPUS5 PR-05: the DB password was never checked at all ---

	@Test
	void productionWithTheCommittedDevDatabasePasswordFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(properties, "MagtiAppDev2026Pw").verify());
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"));
	}

	@Test
	void productionWithAPlaceholderDatabasePasswordFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(properties, "CHANGE_ME_TO_A_STRONG_PASSWORD").verify());
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"));
	}

	@Test
	void productionWithApplicationYmlsNewLocalPlaceholderFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(properties, "CHANGE_ME_LOCAL_ONLY").verify());
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"));
	}

	@Test
	void productionWithNoDatabasePasswordFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(properties, "").verify());
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"));
	}

	// --- audit OPUS5 SEC-01: the bypass may never be combined with production ---

	@Test
	void productionWithTheDevLoginFlagFailsLoud() {
		PortalProperties properties = propertiesWith("production", REAL_SECRET, true);
		properties.getSecurity().setAllowDevLogin(true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> guardFor(properties).verify());
		assertTrue(ex.getMessage().contains("ALLOW_DEV_LOGIN"));
	}

	@Test
	void devLoginFlagIsAllowedOutsideProduction() {
		PortalProperties properties = propertiesWith(
				"development", "super-secret-temporary-key-for-local-development", false);
		properties.getSecurity().setAllowDevLogin(true);
		assertDoesNotThrow(() -> guardFor(properties).verify());
	}
}
