package ge.magti.portal.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyGuardTest {

	private static PortalProperties propertiesWith(String appEnv, String jwtSecret, boolean cookieSecure) {
		PortalProperties properties = new PortalProperties();
		properties.setAppEnv(appEnv);
		properties.getSecurity().getJwt().setSecret(jwtSecret);
		properties.getSecurity().getCookie().setSecure(cookieSecure);
		return properties;
	}

	@Test
	void devEnvironmentIsNeverChecked() {
		PortalProperties properties = propertiesWith(
				"development", "super-secret-temporary-key-for-local-development", false);
		assertDoesNotThrow(() -> new ProductionSafetyGuard(properties).verify());
	}

	@Test
	void productionWithDevSecretFailsLoud() {
		PortalProperties properties = propertiesWith(
				"production", "super-secret-temporary-key-for-local-development", true);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> new ProductionSafetyGuard(properties).verify());
		assertTrue(ex.getMessage().contains("SECRET_KEY"));
	}

	@Test
	void productionWithInsecureCookieFailsLoud() {
		PortalProperties properties = propertiesWith("production", "a-real-generated-secret", false);
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> new ProductionSafetyGuard(properties).verify());
		assertTrue(ex.getMessage().contains("COOKIE_SECURE"));
	}

	@Test
	void productionWithRealSecretAndSecureCookiePasses() {
		PortalProperties properties = propertiesWith("production", "a-real-generated-secret", true);
		assertDoesNotThrow(() -> new ProductionSafetyGuard(properties).verify());
	}
}
