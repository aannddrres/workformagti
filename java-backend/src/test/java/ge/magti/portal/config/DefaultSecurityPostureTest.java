package ge.magti.portal.config;

import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit OPUS5 SEC-01: proves the SHIPPED defaults are safe -- that a
 * deployment which sets no environment variable at all gets the
 * password-less admin bypass OFF, not ON.
 *
 * <p>Reads src/main/resources/application.yml directly and binds it through
 * an isolated {@link MutablePropertySources} that contains nothing else: no
 * system properties, no OS environment, and NOT the dev profile the rest of
 * the test run activates (src/test/resources/application.properties). So
 * this asserts what a real deployment gets from the jar, and cannot be
 * masked by the machine it runs on.
 *
 * <p>Covers both halves of the default, which live in two different files
 * and drifted apart before: the Java field defaults in
 * {@link PortalProperties} and the {@code ${VAR:default}} fallbacks in
 * application.yml.
 */
class DefaultSecurityPostureTest {

	private static PortalProperties shippedYamlDefaults() throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"));
		loaded.forEach(sources::addLast);

		Binder binder = new Binder(
				ConfigurationPropertySources.from(sources),
				new PropertySourcesPlaceholdersResolver(sources));
		return binder.bind("portal", Bindable.ofInstance(new PortalProperties())).get();
	}

	/** Same isolated binding, but with application-dev.yml layered on top, exactly as the dev profile does. */
	private static PortalProperties devProfileDefaults() throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		// addFirst for the profile document: profile-specific values win.
		new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"))
				.forEach(sources::addLast);
		new YamlPropertySourceLoader()
				.load("application-dev.yml", new ClassPathResource("application-dev.yml"))
				.forEach(sources::addFirst);

		return new Binder(
				ConfigurationPropertySources.from(sources),
				new PropertySourcesPlaceholdersResolver(sources))
				.bind("portal", Bindable.ofInstance(new PortalProperties())).get();
	}

	private static String shippedDatabasePasswordDefault() throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"))
				.forEach(sources::addLast);

		return new Binder(
				ConfigurationPropertySources.from(sources),
				new PropertySourcesPlaceholdersResolver(sources))
				.bind("spring.datasource.password", Bindable.of(String.class))
				.get();
	}

	@Test
	void javaFieldDefaultsAreProductionWithTheBypassOff() {
		PortalProperties fresh = new PortalProperties();

		assertEquals("production", fresh.getAppEnv());
		assertTrue(fresh.isProduction());
		assertFalse(fresh.getSecurity().isAllowDevLogin());
		assertFalse(fresh.isDevLoginEnabled());
	}

	@Test
	void shippedYamlDefaultsAreProductionWithTheBypassOff() throws IOException {
		PortalProperties shipped = shippedYamlDefaults();

		assertEquals("production", shipped.getAppEnv(), "application.yml's APP_ENV fallback");
		assertFalse(shipped.getSecurity().isAllowDevLogin(), "application.yml's ALLOW_DEV_LOGIN fallback");
		assertFalse(shipped.isDevLoginEnabled());
	}

	/**
	 * The finding itself, end to end: with no env vars set, the login that
	 * used to return a SYSTEM_ADMIN token for any password returns nothing,
	 * and provisions no account.
	 */
	@Test
	void withNoEnvironmentVariablesAdminCannotLogInWithAnArbitraryPassword() throws IOException {
		UserRepository userRepository = mock(UserRepository.class);
		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
		when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

		AuthenticationService service =
				new AuthenticationService(userRepository, passwordEncoder, shippedYamlDefaults());

		Optional<User> admin = service.authenticate("admin@magti.ge", "literally-anything");
		Optional<User> provisionedOperator = service.authenticate("test_operator_42@magti.ge", "whatever");

		assertTrue(admin.isEmpty(), "password-less admin bypass must be off by default");
		assertTrue(provisionedOperator.isEmpty(), "test_operator_* JIT provisioning must be off by default");
		verify(userRepository, never()).save(any());
	}

	/**
	 * The other half of the contract: flipping the defaults must not take
	 * local development away with it. The dev profile has to put the bypass
	 * back, or every developer and the whole integration-test suite (which
	 * logs in as content@magti.ge with any password) stops working.
	 */
	@Test
	void devProfilePutsTheLocalDevelopmentBypassBack() throws IOException {
		PortalProperties dev = devProfileDefaults();

		assertEquals("development", dev.getAppEnv());
		assertTrue(dev.getSecurity().isAllowDevLogin());
		assertTrue(dev.isDevLoginEnabled());

		UserRepository userRepository = mock(UserRepository.class);
		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
		when(userRepository.findByEmailIgnoreCase("content@magti.ge")).thenReturn(Optional.empty());
		when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

		AuthenticationService service =
				new AuthenticationService(userRepository, passwordEncoder, dev);

		assertTrue(service.authenticate("content@magti.ge", "anything").isPresent());
	}

	/**
	 * The shipped DB password default must be a placeholder the guard
	 * rejects, not a real-looking credential (PR-05).
	 */
	@Test
	void shippedDatabasePasswordDefaultIsAPlaceholderTheGuardRejects() throws IOException {
		String shippedDefault = shippedDatabasePasswordDefault();

		assertEquals("CHANGE_ME_LOCAL_ONLY", shippedDefault);

		PortalProperties production = new PortalProperties();
		production.setAppEnv("production");
		production.getSecurity().getJwt()
				.setSecret("Xq7fLp2VtRn9WzKd4HsYbA6MjCe1UgTv8QoZxNr3PwLiEy5BkDaS0FcHmJuOtIvR");
		production.getSecurity().getCookie().setSecure(true);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(production, shippedDefault).verify());
		assertTrue(ex.getMessage().contains("ORACLE_DB_PASSWORD"));
	}
}
