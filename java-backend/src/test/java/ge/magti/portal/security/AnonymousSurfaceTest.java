package ge.magti.portal.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@link SecurityConfig}'s anonymous allowlist and the access contract
 * from disagreeing.
 *
 * <p>The filter chain denies by default, so exactly one thing decides whether
 * an endpoint can be reached without a token: whether a pattern in
 * {@code ANONYMOUS_GET}/{@code ANONYMOUS_POST} matches it. That makes the
 * allowlist a security boundary written as two string arrays, and a string
 * array is easy to widen by accident -- {@code "/api/auth/**"} instead of
 * three explicit paths reopens every future {@code /api/auth/*} endpoint, and
 * nothing at runtime would say so.
 *
 * <p>So the allowlist is checked against {@code
 * docs/ACCESS_CONTRACT_MATRIX_KA.md}, whose {@code gate (დღეს)} column
 * {@code AccessContractCoverageTest} already keeps synchronised with the
 * controllers.
 *
 * <p><b>The check runs allowlist-to-contract, not the reverse.</b> The
 * contract shows seven endpoints with no {@code require*} call -- the four
 * {@code /command} dispatchers, which gate inside the branch they dispatch
 * to, and {@code GET /uploads/{filename}}, which gates with an inline null
 * check. None of them is on the allowlist and none of them should be: with
 * deny-by-default they answer 401 to an anonymous caller, which is the
 * intended behaviour. Reading the empty gate cell as "must be anonymous"
 * would demand the opposite.
 *
 * <p>Reads source text rather than the live bean, for the same reason
 * {@code AccessContractCoverageTest} does: the property being asserted is a
 * property of what is written in the file, and a reader comparing the two
 * files by hand is exactly what this replaces.
 *
 * <p>Adapted from the version on {@code claude/r5-complete-r6-planning}.
 */
class AnonymousSurfaceTest {

	private static final Path SECURITY_CONFIG =
			Path.of("src/main/java/ge/magti/portal/security/SecurityConfig.java");
	private static final List<Path> MATRIX_CANDIDATES = List.of(
			Path.of("../docs/ACCESS_CONTRACT_MATRIX_KA.md"),
			Path.of("docs/ACCESS_CONTRACT_MATRIX_KA.md"));

	private static final Pattern MATRIX_ROW = Pattern.compile(
			"^\\|\\s*`(GET|POST|PUT|DELETE|PATCH) ([^`]*)`\\s*\\|\\s*`([^`]*)`\\s*"
					+ "\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|\\s*$");
	private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

	/** The cell AccessContractCoverageTest renders when a handler calls no require*() helper. */
	private static final String NO_GATE = "—";

	// ---------------------------------------------------------------- source

	private static String securityConfig() throws IOException {
		assertTrue(Files.exists(SECURITY_CONFIG),
				"expected to run from the java-backend module; " + SECURITY_CONFIG);
		return Files.readString(SECURITY_CONFIG);
	}

	/**
	 * SecurityConfig's javadoc quotes the very expressions these tests look
	 * for, so scanning the raw file would find {@code permitAll} in prose and
	 * report a security regression that is a sentence.
	 */
	private static String securityConfigCode() throws IOException {
		return securityConfig().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
	}

	private static List<String> allowlist(String source, String field) {
		Matcher declaration =
				Pattern.compile("String\\[\\]\\s+" + field + "\\s*=\\s*\\{([^}]*)}").matcher(source);
		assertTrue(declaration.find(),
				field + " is no longer declared as a String[] literal in SecurityConfig");
		List<String> patterns = new ArrayList<>();
		Matcher value = QUOTED.matcher(declaration.group(1));
		while (value.find()) {
			patterns.add(value.group(1));
		}
		assertTrue(!patterns.isEmpty(),
				field + " parsed as empty; the regex above no longer matches the source");
		return patterns;
	}

	/** Ant-style, restricted to the two forms the allowlist actually uses. */
	private static boolean covers(String pattern, String path) {
		if (pattern.endsWith("/**")) {
			return path.startsWith(pattern.substring(0, pattern.length() - 2));
		}
		return pattern.equals(path);
	}

	private static Map<String, List<String>> allowlists() throws IOException {
		String source = securityConfig();
		Map<String, List<String>> byVerb = new LinkedHashMap<>();
		byVerb.put("GET", allowlist(source, "ANONYMOUS_GET"));
		byVerb.put("POST", allowlist(source, "ANONYMOUS_POST"));
		return byVerb;
	}

	// ---------------------------------------------------------------- matrix

	private record Row(String verb, String path, String gates) {
		String key() {
			return verb + " " + path;
		}
	}

	private static List<Row> matrixRows() throws IOException {
		Path matrix = MATRIX_CANDIDATES.stream().filter(Files::exists).findFirst().orElseThrow(
				() -> new AssertionError(
						"ACCESS_CONTRACT_MATRIX_KA.md not found; looked in " + MATRIX_CANDIDATES));
		List<Row> rows = new ArrayList<>();
		for (String line : Files.readString(matrix).split("\n", -1)) {
			Matcher m = MATRIX_ROW.matcher(line.strip());
			if (m.matches()) {
				rows.add(new Row(m.group(1), m.group(2), m.group(4).strip().replace("`", "")));
			}
		}
		assertTrue(rows.size() > 100, "parsed only " + rows.size() + " contract rows; the table format changed");
		return rows;
	}

	// ----------------------------------------------------------------- tests

	/**
	 * The direction that protects: nothing reaches the allowlist unless the
	 * contract already records it as having no gate of its own.
	 *
	 * <p>Catches both ways of widening the surface -- adding a real endpoint
	 * to the array, and writing a pattern broader than intended, since the
	 * pattern is matched against every path in the contract.
	 */
	@Test
	void everythingOnTheAllowlistIsRecordedAsUngatedInTheContract() throws IOException {
		Map<String, List<String>> allowlists = allowlists();
		List<Row> rows = matrixRows();

		List<String> reopened = new ArrayList<>();
		List<String> unknown = new ArrayList<>();

		for (Map.Entry<String, List<String>> entry : allowlists.entrySet()) {
			for (String pattern : entry.getValue()) {
				List<Row> matched = rows.stream()
						.filter(row -> row.verb().equals(entry.getKey()) && covers(pattern, row.path()))
						.toList();
				if (matched.isEmpty()) {
					unknown.add(entry.getKey() + " " + pattern);
				}
				matched.stream()
						.filter(row -> !NO_GATE.equals(row.gates()))
						.forEach(row -> reopened.add(row.key() + " (gate: " + row.gates() + ")"));
			}
		}

		assertTrue(reopened.isEmpty(),
				"SecurityConfig's anonymous allowlist matches these GATED endpoints, so the filter chain lets an "
						+ "anonymous request reach them and only the endpoint's own guard is left. Usually this means "
						+ "a pattern is broader than it looks: " + reopened);
		assertTrue(unknown.isEmpty(),
				"these allowlist patterns match no endpoint in the access contract. Either the endpoint is gone and "
						+ "the allowlist should shrink, or it exists and is missing a contract row: " + unknown);
	}

	@Test
	void theChainStillDeniesByDefault() throws IOException {
		String source = securityConfigCode();

		assertTrue(source.contains("anyRequest().authenticated()"),
				"SecurityConfig no longer ends in anyRequest().authenticated(); every endpoint without its own "
						+ "require*() guard is open again");
		assertTrue(!source.contains("anyRequest().permitAll()"),
				"SecurityConfig is back to permitting everything by default");
	}

	/**
	 * The allowlist arrays are not the only way to permit a path -- an inline
	 * {@code .requestMatchers(...).permitAll()} does it too, and would be
	 * invisible to the test above. There are exactly three permits in the
	 * chain, one per array.
	 */
	@Test
	void nothingIsPermittedOutsideTheThreeNamedArrays() throws IOException {
		String chain = securityConfigCode();
		int permits = 0;
		Matcher m = Pattern.compile("\\.permitAll\\(\\)").matcher(chain);
		while (m.find()) {
			permits++;
		}

		assertEquals(3, permits,
				"expected exactly three permitAll() calls (ANONYMOUS_POST, ANONYMOUS_GET, ANONYMOUS_PROBES); "
						+ "a fourth is an anonymous path that neither this test nor the access contract can see");
	}

	/**
	 * The probes are held apart from the API allowlist on purpose, and that
	 * separation is what keeps the contract check above exact. Merging them
	 * would put two Actuator paths -- which have no contract row and never
	 * will -- into the array that must match the contract exactly.
	 */
	@Test
	void theProbeAllowlistCarriesOnlyActuatorPaths() throws IOException {
		List<String> probes = allowlist(securityConfig(), "ANONYMOUS_PROBES");

		List<String> notProbes = probes.stream().filter(p -> !p.startsWith("/actuator/")).toList();
		assertTrue(notProbes.isEmpty(),
				"ANONYMOUS_PROBES is for Actuator's operational endpoints only; these are portal paths and belong "
						+ "in ANONYMOUS_GET, where the access contract can see them: " + notProbes);
	}
}
