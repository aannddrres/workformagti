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
 * array is easy to widen by accident -- {@code "/api/auth/**"} instead of two
 * explicit paths reopens every future {@code /api/auth/*} endpoint, and
 * nothing at runtime would say so.
 *
 * <p>So the allowlist is checked against {@code
 * docs/ACCESS_CONTRACT_MATRIX_KA.md}, whose {@code gate (დღეს)} column
 * {@link AccessContractCoverageTest} already keeps synchronised with the
 * controllers. Two directions, and the second is the one that matters:
 *
 * <ol>
 *   <li><b>Every endpoint the contract shows as ungated is in the
 *       allowlist.</b> Otherwise it now answers 401 -- which may well be the
 *       right call, but is a behaviour change that has to be made on purpose
 *       and written down, not discovered by a user.
 *   <li><b>No endpoint the contract shows as gated is in the allowlist.</b>
 *       This is what catches a pattern that is broader than it looks. An
 *       endpoint reachable anonymously still runs its own {@code require*}
 *       guard, so the immediate damage is bounded -- but the floor is gone,
 *       and the floor exists precisely for the endpoint whose guard is
 *       missing.
 * </ol>
 *
 * <p>Reads source text rather than the live bean, for the same reason
 * {@link AccessContractCoverageTest} does: the property being asserted is a
 * property of what is written in the file, and a reader comparing the two
 * files by hand is exactly what this replaces.
 */
class AnonymousSurfaceTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src/main/java/ge/magti/portal/security/SecurityConfig.java");
    private static final List<Path> MATRIX_CANDIDATES = List.of(
            Path.of("../docs/ACCESS_CONTRACT_MATRIX_KA.md"),
            Path.of("docs/ACCESS_CONTRACT_MATRIX_KA.md"));

    private static final Pattern MATRIX_ROW = Pattern.compile(
            "^\\|\\s*`(GET|POST|PUT|DELETE|PATCH) ([^`]*)`\\s*\\|\\s*`([^`]*)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|\\s*$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /** The cell AccessContractCoverageTest renders when a handler calls no require*() helper. */
    private static final String NO_GATE = "—";

    // ---------------------------------------------------------------- source

    private static String securityConfig() throws IOException {
        assertTrue(Files.exists(SECURITY_CONFIG), "expected to run from the java-backend module; " + SECURITY_CONFIG);
        return Files.readString(SECURITY_CONFIG);
    }

    /**
     * SecurityConfig's javadoc quotes the very expressions these tests look
     * for ({@code anyRequest().permitAll()} is named there as the thing that
     * changed). Scanning the raw file would therefore find "permitAll" in
     * prose and report a security regression that is a sentence.
     */
    private static String securityConfigCode() throws IOException {
        return securityConfig().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static List<String> allowlist(String source, String field) {
        Matcher declaration = Pattern.compile("String\\[\\]\\s+" + field + "\\s*=\\s*\\{([^}]*)}").matcher(source);
        assertTrue(declaration.find(), field + " is no longer declared as a String[] literal in SecurityConfig");
        List<String> patterns = new ArrayList<>();
        Matcher value = QUOTED.matcher(declaration.group(1));
        while (value.find()) {
            patterns.add(value.group(1));
        }
        assertTrue(!patterns.isEmpty(), field + " parsed as empty; the regex above no longer matches the source");
        return patterns;
    }

    /** Ant-style, restricted to the two forms the allowlist actually uses. */
    private static boolean covers(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        return pattern.equals(path);
    }

    private static boolean anonymous(Map<String, List<String>> allowlists, String verb, String path) {
        return allowlists.getOrDefault(verb, List.of()).stream().anyMatch(pattern -> covers(pattern, path));
    }

    // ---------------------------------------------------------------- matrix

    private record Row(String verb, String path, String gates) {
        String key() {
            return verb + " " + path;
        }
    }

    private static List<Row> matrixRows() throws IOException {
        Path matrix = MATRIX_CANDIDATES.stream().filter(Files::exists).findFirst().orElseThrow(
                () -> new AssertionError("ACCESS_CONTRACT_MATRIX_KA.md not found; looked in " + MATRIX_CANDIDATES));
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

    private static Map<String, List<String>> allowlists() throws IOException {
        String source = securityConfig();
        Map<String, List<String>> byVerb = new LinkedHashMap<>();
        byVerb.put("GET", allowlist(source, "ANONYMOUS_GET"));
        byVerb.put("POST", allowlist(source, "ANONYMOUS_POST"));
        return byVerb;
    }

    // ----------------------------------------------------------------- tests

    @Test
    void everyUngatedEndpointIsOnTheAllowlist() throws IOException {
        Map<String, List<String>> allowlists = allowlists();
        List<String> nowDenied = matrixRows().stream()
                .filter(row -> NO_GATE.equals(row.gates()))
                .filter(row -> !anonymous(allowlists, row.verb(), row.path()))
                .map(Row::key)
                .toList();

        assertTrue(nowDenied.isEmpty(),
                "these endpoints have no gate of their own and are not on SecurityConfig's anonymous allowlist, "
                        + "so they now answer 401 to every caller without a token. If that is intended, give them a "
                        + "gate and update the contract; if not, add them to the allowlist: " + nowDenied);
    }

    @Test
    void noGatedEndpointIsReachableAnonymously() throws IOException {
        Map<String, List<String>> allowlists = allowlists();
        List<String> reopened = matrixRows().stream()
                .filter(row -> !NO_GATE.equals(row.gates()))
                .filter(row -> anonymous(allowlists, row.verb(), row.path()))
                .map(row -> row.key() + " (gate: " + row.gates() + ")")
                .toList();

        assertTrue(reopened.isEmpty(),
                "SecurityConfig's anonymous allowlist matches these gated endpoints, so the filter chain lets an "
                        + "anonymous request reach them and only the endpoint's own guard is left. Usually this means "
                        + "a pattern is broader than it looks: " + reopened);
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
     * invisible to the two tests above. There are exactly three permits in the
     * chain: the two arrays and {@code /error}.
     */
    @Test
    void nothingIsPermittedOutsideTheTwoArraysAndTheErrorForward() throws IOException {
        String chain = securityConfigCode();
        int permits = 0;
        Matcher m = Pattern.compile("\\.permitAll\\(\\)").matcher(chain);
        while (m.find()) {
            permits++;
        }

        assertEquals(3, permits,
                "expected exactly three permitAll() calls (ANONYMOUS_GET, ANONYMOUS_POST, /error); a fourth is an "
                        + "anonymous path that neither AnonymousSurfaceTest nor the access contract can see");
        assertTrue(chain.contains(".requestMatchers(\"/error\").permitAll()"),
                "the /error forward must stay permitted, or Spring's own error rendering answers 401 instead of the "
                        + "status the request actually produced");
    }
}
