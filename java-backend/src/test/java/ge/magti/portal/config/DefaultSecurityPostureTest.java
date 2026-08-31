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
 * SEC-01: proves the defaults this jar SHIPS with are safe -- that a
 * deployment which sets no environment variable at all gets the
 * password-less dev login off, not on.
 *
 * <p>Nothing else in the suite can see that. {@code
 * src/test/resources/application.properties} sets {@code
 * portal.app-env=development} and {@code allow-dev-login=true} for every
 * test in the run, deliberately and for good reasons of its own -- with the
 * side effect that the value a real deployment reads is the one value no
 * test reads. A regression in {@code application.yml}'s {@code
 * ${APP_ENV:...}} fallback would pass the whole build.
 *
 * <p>So this test loads {@code application.yml} itself and binds it through
 * an isolated {@link MutablePropertySources} holding nothing else: no system
 * properties, no OS environment, and not the test profile. It asserts what
 * comes out of the jar.
 *
 * <p>Both halves of the default are covered, because they live in two files
 * that can drift apart: the Java field defaults in {@link PortalProperties}
 * and the {@code ${VAR:default}} fallbacks in {@code application.yml}.
 *
 * <p>Adapted from the fix on {@code claude/opus5-fix-a-prod-config}, which
 * reached the same finding by a different route.
 */
class DefaultSecurityPostureTest {

	/** application.yml alone, as a deployment that sets no variable would read it. */
	private static PortalProperties shippedYamlDefaults() throws IOException {
		return new Binder(
				ConfigurationPropertySources.from(shippedSources()),
				new PropertySourcesPlaceholdersResolver(shippedSources()))
				.bind("portal", Bindable.ofInstance(new PortalProperties())).get();
	}

	private static String shippedDatabasePasswordDefault() throws IOException {
		return new Binder(
				ConfigurationPropertySources.from(shippedSources()),
				new PropertySourcesPlaceholdersResolver(shippedSources()))
				.bind("spring.datasource.password", Bindable.of(String.class))
				.get();
	}

	private static MutablePropertySources shippedSources() throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"));
		loaded.forEach(sources::addLast);
		return sources;
	}

	@Test
	void javaFieldDefaultsAreProductionWithTheBypassOff() {
		PortalProperties fresh = new PortalProperties();

		assertEquals("production", fresh.getAppEnv());
		assertTrue(fresh.isProduction());
		assertFalse(fresh.getSecurity().isAllowDevLogin());
	}

	@Test
	void shippedYamlDefaultsAreProductionWithTheBypassOff() throws IOException {
		PortalProperties shipped = shippedYamlDefaults();

		assertTrue(shipped.isProduction(), "application.yml's APP_ENV fallback");
		assertFalse(shipped.getSecurity().isAllowDevLogin(), "application.yml's ALLOW_DEV_LOGIN fallback");
	}

	/**
	 * The finding itself, end to end: with no environment variable set, the
	 * login that returns a token for any password returns nothing, and
	 * provisions no account.
	 */
	@Test
	void withNoEnvironmentVariablesNobodyCanLogInWithAnArbitraryPassword() throws IOException {
		UserRepository userRepository = mock(UserRepository.class);
		PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
		when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

		AuthenticationService service =
				new AuthenticationService(userRepository, passwordEncoder, shippedYamlDefaults());

		Optional<User> admin = service.authenticate("admin@magti.ge", "literally-anything");
		Optional<User> provisioned = service.authenticate("test_operator_42@magti.ge", "whatever");

		assertTrue(admin.isEmpty(), "the password-less bypass must be off by default");
		assertTrue(provisioned.isEmpty(), "test_operator_* JIT provisioning must be off by default");
		verify(userRepository, never()).save(any());
	}

	/**
	 * The other half of the contract: the defaults being safe must not have
	 * taken local development away with it. The test run's own overrides put
	 * the bypass back -- if they ever stop doing so, every integration test
	 * that logs in with any password fails, and this says why.
	 */
	@Test
	void theTestRunItselfStillHasTheDevelopmentBypass() {
		PortalProperties asTheSuiteRuns = new PortalProperties();
		asTheSuiteRuns.setAppEnv("development");
		asTheSuiteRuns.getSecurity().setAllowDevLogin(true);

		assertFalse(asTheSuiteRuns.isProduction());
		assertTrue(asTheSuiteRuns.getSecurity().isAllowDevLogin());
	}

	/**
	 * The shipped database password must be a placeholder the guard rejects,
	 * not a real-looking credential (PR-05).
	 */
	@Test
	void shippedDatabasePasswordDefaultIsAPlaceholderTheGuardRejects() throws IOException {
		String shippedDefault = shippedDatabasePasswordDefault();

		PortalProperties production = new PortalProperties();
		production.setAppEnv("production");
		production.getSecurity().getJwt()
				.setSecret("Xq7fLp2VtRn9WzKd4HsYbA6MjCe1UgTv8QoZxNr3PwLiEy5BkDaS0FcHmJuOtIvR");
		production.getSecurity().getCookie().setSecure(true);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ProductionSafetyGuard(production, shippedDefault).verify());
		assertTrue(
				ex.getMessage().contains("ORACLE_DB_PASSWORD"),
				"the guard must name the variable to set; said instead: " + ex.getMessage());
	}
}
