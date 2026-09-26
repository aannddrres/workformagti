package ge.magti.portal.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	/** ASVS V3.3.2: Lax would send the session cookie on a cross-site top-level GET. */
	@Test
	void productionWithNonStrictSameSiteFailsLoud() {
		for (String weaker : new String[] {"lax", "Lax", "none", ""}) {
			PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
			properties.getSecurity().getCookie().setSameSite(weaker);
			IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify(), weaker);
			assertTrue(ex.getMessage().contains("COOKIE_SAMESITE"), ex.getMessage());
		}
	}

	@Test
	void productionWithRealSecretAndSecureCookiePasses() {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		assertDoesNotThrow(() -> guard(properties).verify());
	}

	// --- Exhaustive over the lists, not over the flags -------------------
	//
	// The four risks above are checked by four independent `if (risk) throw`
	// statements, each reading only its own axis, so the sixteen-way truth
	// table over those flags is implied by the four single-risk tests and
	// would only restate them. The combinatorial surface that ISN'T covered
	// is inside two of those checks: PLACEHOLDER_MARKERS has ten entries and
	// KNOWN_DEV_DB_PASSWORDS five, and the tests above exercise two of each.
	// An entry that does not actually reject anything is indistinguishable
	// from one that does, until the day it is the only thing between a
	// shipped placeholder and production.

	/**
	 * Every marker in the guard's own list, read off the field rather than
	 * copied here. A duplicated list would drift the moment somebody adds a
	 * marker, and would drift silently in the direction of testing less than
	 * the code does.
	 */
	@Test
	void everyPlaceholderMarkerIsRejectedAsASecret() {
		List<String> notRejected = new ArrayList<>();
		for (String marker : listField("PLACEHOLDER_MARKERS")) {
			// Long and varied enough to clear the length and entropy rules,
			// so the only thing left that can reject it is the marker.
			String secret = STRONG_SECRET + marker;
			PortalProperties properties = propertiesWith("production", secret, true);
			try {
				guard(properties).verify();
				notRejected.add(marker);
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().contains("placeholder")) {
					notRejected.add(marker + " (rejected, but not as a placeholder: "
							+ expected.getMessage() + ")");
				}
			}
		}

		assertEquals(List.of(), notRejected,
				"these entries of PLACEHOLDER_MARKERS do not actually reject a secret containing them");
	}

	/** Same, for the shipped database passwords (PR-05). */
	@Test
	void everyKnownDevDatabasePasswordIsRejected() {
		List<String> notRejected = new ArrayList<>();
		for (String password : listField("KNOWN_DEV_DB_PASSWORDS")) {
			PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
			try {
				new ProductionSafetyGuard(properties, password).verify();
				notRejected.add(password);
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().contains("ORACLE_DB_PASSWORD")) {
					notRejected.add(password + " (rejected for the wrong reason: "
							+ expected.getMessage() + ")");
				}
			}
		}

		assertEquals(List.of(), notRejected,
				"these entries of KNOWN_DEV_DB_PASSWORDS do not actually reject that password");
	}

	@SuppressWarnings("unchecked")
	private static List<String> listField(String name) {
		try {
			Field field = ProductionSafetyGuard.class.getDeclaredField(name);
			field.setAccessible(true);
			List<String> values = (List<String>) field.get(null);
			assertTrue(!values.isEmpty(), name + " is empty -- the test below would pass vacuously");
			return values;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(
					"ProductionSafetyGuard." + name + " is gone or renamed -- this test reads it directly so "
							+ "that adding an entry cannot silently go untested", e);
		}
	}

	/**
	 * The guard checks nothing outside production, deliberately, and the one
	 * dev test above proves it for one spelling of one environment. This
	 * pins the scope for the rest: any non-production value skips every
	 * check, however bad the configuration behind it.
	 */
	@Test
	void noNonProductionEnvironmentIsEverChecked() {
		List<String> refused = new ArrayList<>();
		for (String appEnv : List.of("development", "dev", "test", "local")) {
			PortalProperties properties =
					propertiesWith(appEnv, "change-me-to-a-long-random-value", false);
			properties.getSecurity().setAllowDevLogin(true);
			try {
				new ProductionSafetyGuard(properties, "MagtiAppDev2026Pw").verify();
			} catch (IllegalStateException e) {
				refused.add(appEnv + ": " + e.getMessage());
			}
		}

		assertEquals(List.of(), refused,
				"the guard refused a non-production environment -- it is scoped to production on purpose, "
						+ "and widening it here would break every developer's machine");
	}

	/**
	 * DEC-P04, fixed. Surrounding whitespace no longer decides whether a
	 * deployment is production.
	 *
	 * <p>It used to: {@code isProduction()} was {@code equalsIgnoreCase} and
	 * nothing else, so {@code APP_ENV=production } with one trailing space --
	 * which .env files and docker compose both preserve -- was not
	 * production. Both callers turn on that answer, so one invisible
	 * character skipped every check in this class AND made the password-less
	 * dev login eligible again ({@code AuthenticationService:80} gates it on
	 * {@code !isProduction()}). Both halves of SEC-01, undone by a space.
	 */
	@Test
	void appEnvIgnoresSurroundingWhitespaceAndCasing() {
		List<String> notRecognised = new ArrayList<>();
		for (String spelling : List.of(
				"production", "PRODUCTION", "Production",
				" production", "production ", "  production  ", "\tproduction\n")) {
			if (!appEnvOf(spelling).isProduction()) {
				notRecognised.add("[" + spelling + "]");
			}
		}

		assertEquals(List.of(), notRecognised,
				"these spellings of APP_ENV are production and must be treated as such -- a value that "
						+ "misses by whitespace disables every check in this class and re-enables the dev login");
	}

	/**
	 * DEC-P04 from this class's side: a production APP_ENV that misses the
	 * exact spelling by whitespace must still be checked. Asserting
	 * {@code isProduction()} alone would not have shown this -- the guard is
	 * what turns that answer into a refused boot.
	 */
	@Test
	void aProductionAppEnvWithStrayWhitespaceIsStillChecked() {
		for (String spelling : List.of(" production", "production ", "\tPRODUCTION\n")) {
			PortalProperties properties =
					propertiesWith(spelling, "change-me-to-a-long-random-value", false);
			properties.getSecurity().setAllowDevLogin(true);

			IllegalStateException ex = assertThrows(IllegalStateException.class,
					() -> new ProductionSafetyGuard(properties, "MagtiAppDev2026Pw").verify(),
					"APP_ENV=[" + spelling + "] booted with the dev login on and a placeholder secret");
			assertTrue(ex.getMessage().contains("allow-dev-login"), ex.getMessage());
		}
	}

	/**
	 * An unset or blank APP_ENV is production, for the same reason the field
	 * defaults to it: the insecure mode is the one that has to be asked for.
	 * {@code APP_ENV=} is a variable somebody meant to set and did not, and a
	 * developer who lands here gets a loud refusal naming APP_ENV rather than
	 * a silent production boot.
	 */
	@Test
	void anUnsetOrBlankAppEnvIsProduction() {
		List<String> treatedAsDevelopment = new ArrayList<>();
		for (String spelling : List.of("", " ", "   ", "\t")) {
			if (!appEnvOf(spelling).isProduction()) {
				treatedAsDevelopment.add("[" + spelling + "]");
			}
		}
		if (!appEnvOf(null).isProduction()) {
			treatedAsDevelopment.add("null");
		}

		assertEquals(List.of(), treatedAsDevelopment,
				"a blank APP_ENV must fail safe to production -- treating it as development is the SEC-01 "
						+ "hole arriving through an empty value instead of a missing one");
	}

	/**
	 * DEC-P05. An APP_ENV that is not a recognised development environment is
	 * production, whatever it says.
	 *
	 * <p>The predicate used to name the production spelling and treat
	 * everything else as development, which meant every value missing from
	 * that one name failed OPEN: {@code prod}, the typo {@code produciton},
	 * an unrelated word. Listing the development names instead makes the same
	 * mistakes fail SAFE -- the guard runs, refuses the boot and names the
	 * variable, rather than starting with the dev login live.
	 *
	 * <p>An allowlist of production spellings would not have fixed this. It
	 * closes the entries somebody thought to add and leaves every typo open,
	 * which is the shape of the bug, not a fix for it.
	 */
	@Test
	void anythingThatIsNotAKnownDevelopmentEnvironmentIsProduction() {
		List<String> failedOpen = new ArrayList<>();
		for (String spelling : List.of(
				"prod", "prd", "live",
				"produciton", "prodcution", "developement",
				"staging", "qa", "uat", "sandbox", "preprod",
				"anything-at-all")) {
			if (!appEnvOf(spelling).isProduction()) {
				failedOpen.add(spelling);
			}
		}

		assertEquals(List.of(), failedOpen,
				"these APP_ENV values are not recognised development environments, so they must be treated "
						+ "as production -- each one that is not disables every check in this class and makes "
						+ "the password-less dev login eligible");
	}

	/**
	 * The other side of the same rule: the insecure posture is still
	 * reachable, and only by naming it. Whitespace and casing are forgiven
	 * because they are never intent.
	 *
	 * <p>Only local-machine names are on this list. staging, qa and uat are
	 * deployed environments other people can reach, so they get the same
	 * checks production does -- a change from the old behaviour, where every
	 * string but "production" skipped all of them.
	 */
	@Test
	void theKnownDevelopmentEnvironmentsAreStillReachable() {
		List<String> notRecognised = new ArrayList<>();
		for (String spelling : List.of(
				"development", "dev", "local", "test",
				"DEVELOPMENT", " dev ", "\tLocal\n")) {
			if (appEnvOf(spelling).isProduction()) {
				notRecognised.add("[" + spelling + "]");
			}
		}

		assertEquals(List.of(), notRecognised,
				"a developer must still be able to ask for the insecure posture by name -- if this fails, "
						+ "local development cannot start at all");
	}

	private static PortalProperties appEnvOf(String appEnv) {
		PortalProperties properties = new PortalProperties();
		properties.setAppEnv(appEnv);
		return properties;
	}

	/**
	 * PR-08, and the half of it that was itself a regression: the first fix
	 * logged the effective security configuration only in the non-production
	 * branch, because the production path returned after its checks -- so the
	 * one environment where the line matters most never printed it.
	 *
	 * <p>It now runs before any check can throw, which is only observable on
	 * a boot the guard aborts. Untested until here, and easy to undo by
	 * moving one statement.
	 */
	@Test
	void theStartupSecurityLineIsLoggedEvenWhenTheGuardRefusesToBoot() {
		ch.qos.logback.classic.Logger logger =
				((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(ProductionSafetyGuard.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		logger.setLevel(Level.INFO);
		try {
			PortalProperties properties = propertiesWith("production", "change-me-to-a-long-random-value", true);
			assertThrows(IllegalStateException.class, () -> guard(properties).verify());

			boolean logged = captured.list.stream()
					.anyMatch(event -> event.getFormattedMessage().startsWith("Startup security config:"));
			assertTrue(logged,
					"the guard aborted the boot without logging the configuration that caused it -- move "
							+ "logEffectiveSecurityConfig() back above the checks (audit PR-08)");
		} finally {
			logger.detachAppender(captured);
			captured.stop();
		}
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

	// ── company login (PO-25: production has no other way in) ─────────────

	private static PortalProperties productionWithCompanyLogin(String serviceUri, String credential, String roleMap) {
		PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
		PortalProperties.Corporate corporate = properties.getSecurity().getCorporate();
		corporate.setEnabled(true);
		corporate.setServiceUri(serviceUri);
		corporate.setClientId("InfoPortal");
		corporate.setClientCredential(credential);
		if (roleMap != null) {
			corporate.setRoleMap(roleMap);
		}
		return properties;
	}

	private static String encodedCredential(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private static Stream<Arguments> invalidClientCredentials() {
		return Stream.of(
				Arguments.of("bad base64", "!invalid!"),
				Arguments.of("Basic prefix", "Basic " + encodedCredential("InfoPortal:fixture")),
				Arguments.of("missing separator", encodedCredential("InfoPortal")),
				Arguments.of("empty client", encodedCredential(":fixture")),
				Arguments.of("empty secret", encodedCredential("InfoPortal:")),
				Arguments.of("different client", encodedCredential("DifferentClient:fixture")),
				Arguments.of("raw control", encodedCredential("InfoPortal:fixture\n")),
				Arguments.of("encoded control", encodedCredential("InfoPortal:fixture%0D")),
				Arguments.of("bad percent escape", encodedCredential("InfoPortal:fixture%xy")),
				Arguments.of("trailing newline", encodedCredential("InfoPortal:fixture") + "\n"));
	}

	@ParameterizedTest(name = "rejects {0} without disclosing credential")
	@MethodSource("invalidClientCredentials")
	void productionRejectsMalformedOrMismatchedClientCredentials(String label, String credential) {
		var logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(ProductionSafetyGuard.class);
		var captured = new ListAppender<ILoggingEvent>();
		captured.start();
		logger.addAppender(captured);
		try {
			var properties = productionWithCompanyLogin("https://oauth.example.test/auth/", credential, null);
			var failure = assertThrows(IllegalStateException.class, () -> guard(properties).verify(), label);
			assertTrue(failure.getMessage().contains("OAUTH_SECRET"));
			String messages = failure.getMessage() + captured.list.stream()
					.map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
			assertFalse(messages.contains(credential), "encoded credential must not be logged");
			assertFalse(messages.contains("fixture"), "decoded secret must not be logged");
		} finally {
			logger.detachAppender(captured);
			captured.stop();
		}
	}

	@Test
	void productionAcceptsFormEncodedClientAndSecretContainingAdditionalColon() {
		var properties = productionWithCompanyLogin("https://oauth.example.test/auth/",
				encodedCredential("Info+Portal%2B:fixture:part%2Btwo"), null);
		properties.getSecurity().getCorporate().setClientId("Info Portal+");
		assertDoesNotThrow(() -> guard(properties).verify());
	}

	/**
	 * A production portal with the company login off admits nobody. That is
	 * legitimate while IT finishes its side, so it boots -- but says so on
	 * every start, rather than leaving the first person at the login screen
	 * to find out.
	 */
	@Test
	void productionWithTheCompanyLoginOffBootsAndSaysNobodyCanSignIn() {
		ch.qos.logback.classic.Logger logger =
				((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(ProductionSafetyGuard.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		try {
			PortalProperties properties = propertiesWith("production", STRONG_SECRET, true);
			assertDoesNotThrow(() -> guard(properties).verify());
			assertTrue(captured.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
					&& event.getFormattedMessage().contains("nobody can sign in")));
		} finally {
			logger.detachAppender(captured);
			captured.stop();
		}
	}

	/** The employee's password travels in the request body. */
	@Test
	void productionCompanyLoginOverPlainHttpFails() {
		PortalProperties properties = productionWithCompanyLogin(
				"http://oauth.example.test/auth/", encodedCredential("InfoPortal:fixture"), null);
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(properties).verify());
		assertTrue(ex.getMessage().contains("https://"));
	}

	@Test
	void productionCompanyLoginWithoutARealClientCredentialFails() {
		assertThrows(IllegalStateException.class, () -> guard(
				productionWithCompanyLogin("https://oauth.example.test/auth/", "", null)).verify());
		assertThrows(IllegalStateException.class, () -> guard(
				productionWithCompanyLogin("https://oauth.example.test/auth/", "replace-me", null)).verify());
	}

	/** An invalid or incomplete map would lock out an essential portal role. */
	@Test
	void productionCompanyLoginWithAnUnparseableRoleMapFails() {
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard(productionWithCompanyLogin(
				"https://oauth.example.test/auth/", encodedCredential("InfoPortal:fixture"), "INFOPORTAL_ADMIN=superuser")).verify());
		assertTrue(ex.getMessage().contains("OAUTH_ROLE_MAP"));
		assertThrows(IllegalStateException.class, () -> guard(productionWithCompanyLogin(
				"https://oauth.example.test/auth/", encodedCredential("InfoPortal:fixture"), "")).verify());
		assertThrows(IllegalStateException.class, () -> guard(productionWithCompanyLogin(
				"https://oauth.example.test/auth/", encodedCredential("InfoPortal:fixture"),
				"INFOPORTAL_OPERATOR=operator")).verify());
	}

	@Test
	void productionWithACompleteCompanyLoginBoots() {
		assertDoesNotThrow(() -> guard(productionWithCompanyLogin(
				"https://oauth.example.test/auth/", encodedCredential("InfoPortal:fixture"), null)).verify());
	}
}
