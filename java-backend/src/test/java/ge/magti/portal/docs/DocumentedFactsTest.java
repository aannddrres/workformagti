package ge.magti.portal.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the handful of facts the agent-facing documents state about the build,
 * so that changing the build without changing the documents fails here rather
 * than misleading whoever reads them next.
 *
 * <p>The failure this exists for is real and was live until 2026-09-05:
 * {@code README.md} announced "Spring Boot 3" from the day of the migration
 * while {@code pom.xml} said 4.1.0. Nobody reads a README against a POM, so a
 * wrong version simply stays wrong — and an agent that believes it reaches for
 * the wrong documentation and the wrong idioms.
 *
 * <p>Only facts that are cheap to derive and expensive to get wrong are pinned:
 * framework and language versions, and the highest migration. Test counts and
 * endpoint counts are deliberately <b>not</b> pinned — they change on almost
 * every commit, so a test asserting them would be edited into agreement rather
 * than believed, which teaches exactly the wrong habit.
 *
 * <p>Comparisons are on the major.minor prefix, so {@code README.md} may say
 * "Spring Boot 4.1" where {@code AGENTS.md} says "Spring Boot 4.1.0". The
 * point is that neither may say 3.
 */
class DocumentedFactsTest {

    private static final Path POM = RepoRoot.path("java-backend/pom.xml");
    private static final Path PACKAGE_JSON = RepoRoot.path("angular-frontend/package.json");
    private static final Path NVMRC = RepoRoot.path("angular-frontend/.nvmrc");
    private static final Path MIGRATIONS = RepoRoot.path("java-backend/src/main/resources/db/migration");

    private static final Path ROOT_AGENTS = RepoRoot.path("AGENTS.md");
    private static final Path ROOT_README = RepoRoot.path("README.md");
    private static final Path BACKEND_AGENTS = RepoRoot.path("java-backend/AGENTS.md");
    private static final Path FRONTEND_AGENTS = RepoRoot.path("angular-frontend/AGENTS.md");

    @Test
    void springBootVersionIsStatedCorrectly() throws IOException {
        String majorMinor = majorMinor(springBootVersion());
        assertStates(ROOT_AGENTS, "Spring Boot " + majorMinor, "the Stack table");
        assertStates(ROOT_README, "Spring Boot " + majorMinor, "the Stack line");
        assertStates(BACKEND_AGENTS, "Spring Boot " + majorMinor, "the opening paragraph");
    }

    @Test
    void javaVersionIsStatedCorrectly() throws IOException {
        String java = single(POM, "<java\\.version>([^<]+)</java\\.version>", "java.version in pom.xml");
        assertStates(ROOT_AGENTS, "Java " + java, "the Stack table");
        assertStates(ROOT_README, "Java " + java, "the Stack line");
        assertStates(BACKEND_AGENTS, "Java " + java, "the opening paragraph");
    }

    @Test
    void angularVersionIsStatedCorrectly() throws IOException {
        String major = majorOf(single(PACKAGE_JSON,
                "\"@angular/core\"\\s*:\\s*\"[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+)\"",
                "@angular/core in package.json"));
        assertStates(ROOT_AGENTS, "Angular " + major, "the Stack table");
        assertStates(ROOT_README, "Angular " + major, "the Stack line");
        assertStates(FRONTEND_AGENTS, "Angular " + major, "the opening paragraph");
    }

    @Test
    void nodeVersionIsStatedCorrectly() throws IOException {
        String node = Files.readString(NVMRC).trim();
        assertStates(ROOT_AGENTS, "Node " + node, "the Stack table");
        assertStates(FRONTEND_AGENTS, node, "the opening paragraph");
    }

    @Test
    void theHighestAndNextMigrationAreStatedCorrectly() throws IOException {
        int highest = highestMigration();
        assertStates(ROOT_AGENTS, "`V" + highest + "`", "the Stack table");
        assertStates(BACKEND_AGENTS, "`V" + (highest + 1) + "`", "the \"Migrations\" section");
    }

    /**
     * The endpoint count the access matrix states about itself.
     *
     * <p>This class deliberately does not pin counts, because a number that
     * moves on most commits gets edited into agreement rather than believed.
     * This one is the exception, and for a specific reason: adding or removing
     * an endpoint <i>already</i> obliges you to edit
     * {@code ACCESS_CONTRACT_MATRIX_KA.md}, because
     * {@link ge.magti.portal.security.AccessContractCoverageTest} fails
     * otherwise. Correcting the summary in the same edit costs nothing, and
     * the alternative is what was true until 2026-09-06: 149 machine-checked
     * rows underneath a sentence announcing 143, in the document this project
     * treats as the authority on who may call what. The rows were right the
     * whole time; only the prose drifted, which is the more misleading half
     * for a person skimming.
     */
    @Test
    void theMatrixStatesItsOwnEndpointCountCorrectly() throws IOException {
        int actual = 0;
        Path sources = RepoRoot.path("java-backend/src/main/java");
        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path java : walk.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                Matcher m = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping").matcher(Files.readString(java));
                while (m.find()) {
                    actual++;
                }
            }
        }
        String matrix = Files.readString(RepoRoot.path("docs/ACCESS_CONTRACT_MATRIX_KA.md"));
        assertTrue(matrix.contains("**" + actual + "** endpoint"),
                "docs/ACCESS_CONTRACT_MATRIX_KA.md summarises its own size, and the source now has "
                        + actual + " @*Mapping annotations. Update the \"ციფრებში\" section and the "
                        + "\"წყარო\" line to say " + actual + ".");
    }

    /**
     * Every nested {@code AGENTS.md} needs a {@code CLAUDE.md} beside it.
     *
     * <p>Claude Code reads {@code CLAUDE.md} and does not read {@code AGENTS.md};
     * the cross-tool convention is the other way round. A nested {@code AGENTS.md}
     * on its own is therefore invisible to Claude Code and fully visible to Codex,
     * which is the worst of both — the file exists, the root document promises it
     * loads automatically, and for one of the two tools it silently does not. That
     * was true here for a day. The short shim that imports the sibling keeps a
     * single copy of the content and satisfies both.
     *
     * <p>The repository root is exempt: its {@code CLAUDE.md} is written by hand
     * and carries Claude-specific sections beyond the import.
     */
    @Test
    void everyNestedAgentsFileHasAClaudeShimBesideIt() throws IOException {
        List<String> unreachable = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(RepoRoot.root(), 3)) {
            for (Path agents : walk.filter(p -> p.getFileName().toString().equals("AGENTS.md"))
                    .filter(p -> !p.getParent().equals(RepoRoot.root()))
                    .filter(p -> !p.toString().contains("node_modules")
                            && !p.toString().contains(".agents")
                            && !p.toString().contains("target"))
                    .toList()) {
                Path shim = agents.resolveSibling("CLAUDE.md");
                if (!Files.exists(shim)) {
                    unreachable.add(RepoRoot.root().relativize(agents).toString().replace('\\', '/'));
                } else if (!Files.readString(shim).contains("@AGENTS.md")) {
                    unreachable.add(RepoRoot.root().relativize(shim).toString().replace('\\', '/')
                            + " (exists but does not import @AGENTS.md)");
                }
            }
        }
        assertEquals(List.of(), unreachable,
                "Claude Code reads CLAUDE.md, not AGENTS.md, so these are invisible to it while Codex reads "
                        + "them fine. Add a CLAUDE.md beside each containing a single @AGENTS.md import.");
    }

    /**
     * Guards the guard. Every assertion above is a substring search, and a
     * substring search against an empty or missing file passes nothing and
     * fails loudly — but a search against a file that was replaced by a stub
     * would fail in a way that looks like a version mismatch. Asserting the
     * documents are of a plausible size first makes that distinction obvious
     * in the failure message.
     */
    @Test
    void theDocumentsBeingCheckedAreActuallyThere() throws IOException {
        for (Path doc : List.of(ROOT_AGENTS, ROOT_README, BACKEND_AGENTS, FRONTEND_AGENTS)) {
            assertTrue(Files.exists(doc), doc + " does not exist");
            assertTrue(Files.readString(doc).length() > 500,
                    doc + " is suspiciously short; the version assertions below would be meaningless");
        }
        assertTrue(highestMigration() >= 48,
                "migration folder scan found V" + highestMigration() + "; the folder is probably not being read");
    }

    // --- reading the build ---------------------------------------------------

    private static String springBootVersion() throws IOException {
        // The parent block, not any managed dependency that happens to carry a version.
        String pom = Files.readString(POM);
        Matcher m = Pattern.compile("<parent>.*?<artifactId>spring-boot-starter-parent</artifactId>\\s*"
                + "<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        if (!m.find()) {
            return fail("spring-boot-starter-parent version not found in " + POM);
        }
        return m.group(1).trim();
    }

    private static int highestMigration() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .map(n -> {
                        Matcher m = Pattern.compile("^V(\\d+)").matcher(n);
                        return m.find() ? Integer.parseInt(m.group(1)) : -1;
                    })
                    .reduce(-1, Integer::max);
        }
    }

    private static String single(Path file, String regex, String what) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(file));
        if (!m.find()) {
            return fail(what + " not found in " + file);
        }
        return m.group(1).trim();
    }

    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static String majorOf(String version) {
        return version.split("\\.")[0];
    }

    // --- the assertion -------------------------------------------------------

    private static void assertStates(Path doc, String expected, String where) throws IOException {
        assertTrue(Files.readString(doc).contains(expected),
                doc.getFileName() + " does not say \"" + expected + "\". The build was changed without "
                        + where + " of " + RepoRoot.root().relativize(doc).toString().replace('\\', '/')
                        + " being changed with it; correct the document rather than this test.");
    }
}
