package ge.magti.portal.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lock behind {@code docs/ACCESS_CONTRACT_MATRIX_KA.md}.
 *
 * <p>Phase 1 of the org-access plan is a "decision lock": every endpoint gets
 * a written capability and data scope before any of it is implemented, so
 * Phase 3's shadow mode has something to diff the new answer against. A
 * markdown table alone cannot do that job -- it is correct on the day it is
 * written and silently wrong after the first refactor, which is the failure
 * mode the whole plan exists to avoid.
 *
 * <p>So the structural half of the matrix is asserted against the source it
 * describes. Three ways to fail:
 *
 * <ol>
 *   <li><b>An endpoint with no contract.</b> A new {@code @*Mapping} that
 *       nobody added a row for. This is the important one: the matrix's value
 *       is catching the endpoint nobody thought about, and a matrix that
 *       silently covers 111 of 112 endpoints cannot do that.
 *   <li><b>A row with no endpoint.</b> A handler was renamed, moved or
 *       deleted and the contract still claims it exists.
 *   <li><b>A gate that no longer matches.</b> {@code requireContentAdmin}
 *       became {@code requireSystemAdmin}, or a guard was dropped, without the
 *       contract being updated. Weakening a gate and updating the row is a
 *       reviewable event; weakening it and not updating the row is how SEC-02
 *       and SEC-03 happened.
 * </ol>
 *
 * <p><b>What this deliberately does NOT check</b> is the other half of the
 * table -- {@code capability (სამიზნე)}, {@code scope}, {@code PII}. Those are
 * decisions, not facts about today's code, and most of them are not
 * implemented until Phases 4-6. Asserting them here would either fail from
 * day one or have to be written to match the code, which would make the
 * contract a mirror of the implementation rather than a constraint on it.
 *
 * <p>Reads source text rather than reflection or bytecode, for the same
 * reason {@code PermissionEnforcementCoverageTest} does: the property is a
 * property of the source, and reaching it reflectively would need every
 * controller instantiated and every branch driven.
 */
class AccessContractCoverageTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final List<Path> MATRIX_CANDIDATES = List.of(
            Path.of("../docs/ACCESS_CONTRACT_MATRIX_KA.md"),
            Path.of("docs/ACCESS_CONTRACT_MATRIX_KA.md"));

    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern HANDLER_NAME = Pattern.compile("\\b(\\w+)\\s*\\(");
    /**
     * {@code require} followed by an UPPERCASE letter. The capital is what
     * separates the guard helpers ({@code requireContentAdmin}) from the local
     * variable {@code requiredCount}, which is not a gate and must not be
     * recorded as one.
     */
    private static final Pattern GATE = Pattern.compile("\\brequire([A-Z]\\w*)\\s*\\(");

    /** A matrix row: 7 cells, first one `VERB /path`. Narrower tables in the same file are ignored. */
    private static final Pattern MATRIX_ROW = Pattern.compile(
            "^\\|\\s*`(GET|POST|PUT|DELETE|PATCH) ([^`]*)`\\s*\\|\\s*`([^`]*)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|\\s*$");

    private record Endpoint(String key, String handler, String gates) {
    }

    // ---------------------------------------------------------------- source

    private static String handlerBody(List<String> lines, int mappingLine) {
        int bodyStart = -1;
        // The window has to clear the longest parameter list in the codebase, not
        // the longest one that existed when this was written. GET /api/audit-logs
        // takes ten @RequestParams; at a 12-line lookahead its opening brace fell
        // just outside, handlerBody returned "" and the endpoint silently read as
        // having no gate at all -- which this test then reported as drift.
        for (int j = mappingLine + 1; j < Math.min(mappingLine + 40, lines.size()); j++) {
            if (lines.get(j).contains("{")) {
                bodyStart = j;
                break;
            }
        }
        if (bodyStart < 0) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        int depth = 0;
        for (int j = bodyStart; j < lines.size(); j++) {
            String line = lines.get(j);
            depth += count(line, '{') - count(line, '}');
            body.append(line).append('\n');
            if (depth <= 0 && j > bodyStart) {
                break;
            }
        }
        return body.toString();
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Endpoint> endpointsInSource() throws IOException {
        Map<String, Endpoint> found = new LinkedHashMap<>();
        List<Path> controllers;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            controllers = files.filter(p -> p.getFileName().toString().endsWith("Controller.java")).sorted().toList();
        }
        for (Path file : controllers) {
            String className = file.getFileName().toString().replace(".java", "");
            List<String> lines = List.of(Files.readString(file).split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = MAPPING.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                String verb = m.group(1).toUpperCase();
                String path = m.group(2) == null ? "" : m.group(2);

                String signature = "";
                for (int j = i + 1; j < Math.min(i + 8, lines.size()); j++) {
                    if (lines.get(j).contains("public ")) {
                        signature = lines.get(j).split("public ", 2)[1];
                        break;
                    }
                }
                Matcher nameMatcher = HANDLER_NAME.matcher(signature);
                String handler = nameMatcher.find() ? nameMatcher.group(1) : "";

                TreeSet<String> gates = new TreeSet<>();
                Matcher gateMatcher = GATE.matcher(handlerBody(lines, i));
                while (gateMatcher.find()) {
                    gates.add("require" + gateMatcher.group(1));
                }

                String key = verb + " " + path;
                found.put(key, new Endpoint(key, className + "." + handler, renderGates(gates)));
            }
        }
        return found;
    }

    private static String renderGates(TreeSet<String> gates) {
        return gates.isEmpty() ? "—" : String.join(", ", gates);
    }

    // ---------------------------------------------------------------- matrix

    private static Path matrixPath() {
        return MATRIX_CANDIDATES.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "ACCESS_CONTRACT_MATRIX_KA.md not found next to the backend module; looked in "
                                + MATRIX_CANDIDATES));
    }

    private static Map<String, Endpoint> endpointsInMatrix() throws IOException {
        Map<String, Endpoint> declared = new LinkedHashMap<>();
        for (String line : Files.readString(matrixPath()).split("\n", -1)) {
            Matcher m = MATRIX_ROW.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1) + " " + m.group(2);
            // The gate cell is rendered as `a`, `b`; compared as a, b.
            String gates = m.group(4).strip().replace("`", "");
            assertTrue(declared.put(key, new Endpoint(key, m.group(3).strip(), gates)) == null,
                    "the contract lists " + key + " more than once");
        }
        return declared;
    }

    // ----------------------------------------------------------------- tests

    @Test
    void everyEndpointInTheSourceHasAContractRow() throws IOException {
        List<String> uncovered = new ArrayList<>(endpointsInSource().keySet());
        uncovered.removeAll(endpointsInMatrix().keySet());

        assertTrue(uncovered.isEmpty(),
                "these endpoints exist but no row in docs/ACCESS_CONTRACT_MATRIX_KA.md declares who may call them "
                        + "-- add a row with its capability and scope before shipping the endpoint: " + uncovered);
    }

    @Test
    void everyContractRowStillMatchesAnEndpoint() throws IOException {
        List<String> stale = new ArrayList<>(endpointsInMatrix().keySet());
        stale.removeAll(endpointsInSource().keySet());

        assertTrue(stale.isEmpty(),
                "the contract still claims these endpoints, but no @*Mapping produces them any more "
                        + "-- delete the rows or fix the paths: " + stale);
    }

    /**
     * The one that makes this a lock rather than a snapshot: a gate cannot be
     * weakened, strengthened or dropped without the written contract moving
     * with it.
     */
    @Test
    void noGateHasChangedWithoutTheContractChangingWithIt() throws IOException {
        Map<String, Endpoint> source = endpointsInSource();
        Map<String, Endpoint> declared = endpointsInMatrix();

        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, Endpoint> entry : source.entrySet()) {
            Endpoint contract = declared.get(entry.getKey());
            if (contract == null) {
                continue; // reported by everyEndpointInTheSourceHasAContractRow
            }
            if (!contract.gates().equals(entry.getValue().gates())) {
                drifted.add(entry.getKey() + ": contract says [" + contract.gates()
                        + "], source has [" + entry.getValue().gates() + "]");
            }
            if (!contract.handler().equals(entry.getValue().handler())) {
                drifted.add(entry.getKey() + ": contract says handler " + contract.handler()
                        + ", source has " + entry.getValue().handler());
            }
        }

        assertTrue(drifted.isEmpty(),
                "the access contract and the code disagree. If the change was intended, update "
                        + "docs/ACCESS_CONTRACT_MATRIX_KA.md in the same commit -- a gate moving is a reviewable "
                        + "event, not a mismatch to quietly fix: " + drifted);
    }

    /** Guards the guard: a parser that silently matched nothing would make all three tests vacuous. */
    @Test
    void theMatrixParserActuallyFindsTheTable() throws IOException {
        assertEquals(endpointsInSource().size(), endpointsInMatrix().size(),
                "source and contract must describe the same number of endpoints");
        assertTrue(endpointsInMatrix().size() > 100,
                "the contract table failed to parse -- check the row format before trusting a green run");
    }
}
