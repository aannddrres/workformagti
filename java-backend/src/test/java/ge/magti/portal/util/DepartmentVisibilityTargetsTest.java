package ge.magti.portal.util;

import ge.magti.portal.docs.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DepartmentMatcher#visibilityTargets} and the inline expression it
 * replaced.
 *
 * <h2>The crash</h2>
 *
 * Six places built the caller's target-department list inline as
 * {@code List.of(user.getDepartment(), prefix, "All")}: the article and news
 * lists, the required readings on one article, the compliance page, and both
 * halves of the home page. {@code List.of} rejects a null element and
 * {@code users.department} is nullable, so a caller with no department did not
 * see an empty list or a refusal — all six threw
 * {@code NullPointerException} and answered 500. The four affected screens are
 * the four an operator opens first.
 *
 * <p>It has never fired, for a reason with an expiry date: every account today
 * comes from the development JIT path, which always writes a department, and
 * {@code PUT /api/users/{id}} refuses to blank one. Real accounts will arrive
 * from Active Directory, where the attribute is routinely empty.
 *
 * <h2>Why the source scan below is part of the fix</h2>
 *
 * A helper only helps while it is used. The inline form is three tokens long,
 * reads perfectly naturally, and is what the next person writing a
 * department-scoped query will type — so the test that keeps this closed is
 * not the null case, which would pass forever once fixed, but the one that
 * notices a seventh site being written the old way.
 */
class DepartmentVisibilityTargetsTest {

    private static final String GROUPED = "ტექნიკური — ჯგუფი 03";
    private static final String PARENT = "ტექნიკური";

    @Test
    void aCallerWithNoDepartmentGetsTheWildcardRatherThanACrash() {
        assertEquals(List.of("All"), DepartmentMatcher.visibilityTargets(null),
                "a null department must resolve to wildcard-targeted content and nothing else — "
                        + "the same answer DepartmentMatcher.matches already gives such a caller");
    }

    @Test
    void theAnswerAgreesWithTheRuleThatReadsIt() {
        // The two halves have to mean the same thing: whatever
        // visibilityTargets puts in a query's IN list is what matches() would
        // have accepted for that caller one article at a time. A null
        // department is the case that used to have no answer at all.
        for (String department : new String[] {null, GROUPED, PARENT, ""}) {
            List<String> targets = DepartmentMatcher.visibilityTargets(department);
            assertTrue(DepartmentMatcher.matches(department, targets),
                    "matches() rejects the very targets visibilityTargets produced for "
                            + (department == null ? "a null department" : "\"" + department + "\""));
        }
    }

    @Test
    void aSubGroupStillReachesItsParentAndTheWildcard() {
        assertEquals(List.of(GROUPED, PARENT, "All"), DepartmentMatcher.visibilityTargets(GROUPED),
                "this is exactly what the inline expression produced, order included; "
                        + "the fix must not quietly narrow or widen anyone's reach");
    }

    @Test
    void aBareParentDoesNotRepeatItself() {
        assertEquals(List.of(PARENT, "All"), DepartmentMatcher.visibilityTargets(PARENT),
                "the inline form emitted the department twice when it had no group suffix; "
                        + "deduplicating changes nothing for an IN clause and is one less thing to explain");
    }

    /**
     * The seventh site. Matching the shape rather than an exact string, so a
     * different variable name or a line break does not slip past.
     */
    @Test
    void nothingBuildsThisListInlineAnyMore() throws IOException {
        Pattern inline = Pattern.compile("List\\.of\\(\\s*\\w+\\.getDepartment\\(\\)");
        List<String> offenders = new ArrayList<>();
        Path sources = RepoRoot.path("java-backend/src/main/java");

        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path java : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (java.getFileName().toString().equals("DepartmentMatcher.java")) {
                    continue; // its javadoc quotes the old form on purpose
                }
                String[] lines = Files.readString(java).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    String trimmed = line.strip();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (inline.matcher(line).find()) {
                        offenders.add(RepoRoot.root().relativize(java).toString().replace('\\', '/')
                                + ":" + (i + 1));
                    }
                }
            }
        }

        assertEquals(List.of(), offenders,
                "List.of() throws on a null element and users.department is nullable, so this crashes "
                        + "with a 500 for any caller who has no department — which is what Active Directory "
                        + "will start producing. Call DepartmentMatcher.visibilityTargets(...) instead.");
    }

    /**
     * Guards the guard: the scan above walks a directory and asserts an empty
     * list, which is exactly what a wrong path would also produce.
     */
    @Test
    void theScanIsActuallyReadingTheSources() throws IOException {
        Path sources = RepoRoot.path("java-backend/src/main/java");
        assertTrue(Files.isDirectory(sources), "not a directory: " + sources);
        long files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertFalse(files < 100,
                "only " + files + " java files found under " + sources + "; the scan is looking in the "
                        + "wrong place and would pass no matter what the code says");
    }
}
