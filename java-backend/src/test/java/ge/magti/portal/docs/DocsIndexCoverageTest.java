package ge.magti.portal.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/README.md} a complete index rather than a snapshot of what
 * existed the day it was written.
 *
 * <p>Before 2026-09-05 there was no index at all: {@code docs/} held 67
 * markdown files and about 54 of them were reachable only by listing the
 * directory. An index solves that exactly once. What keeps it solved is this
 * test — a new document with no row is a document nobody will find, and a row
 * pointing at a file that moved is worse than no row, because it reads as an
 * answer.
 *
 * <p>Three properties, and a fourth that guards the parser:
 *
 * <ol>
 *   <li><b>Every document is indexed.</b> Every {@code .md} under {@code docs/}
 *       is linked from the index.
 *   <li><b>Every index entry resolves.</b> No row points at something that is
 *       not there.
 *   <li><b>Living documents have no broken links.</b> Relative links inside
 *       {@code docs/} resolve — <i>except</i> under {@code docs/archive/}.
 *       Archived text is frozen: a dated audit keeps its original wording even
 *       where that wording points at a file deleted since. Correcting it would
 *       be editing evidence, so those links stay broken deliberately and this
 *       test must not ask otherwise.
 * </ol>
 */
class DocsIndexCoverageTest {

    private static final Path DOCS = RepoRoot.path("docs");
    private static final Path INDEX = DOCS.resolve("README.md");
    private static final Path ARCHIVE = DOCS.resolve("archive");

    /** Markdown link target, minus any {@code #anchor}. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+?)(?:#[^)]*)?\\)");

    @Test
    void everyDocumentUnderDocsIsInTheIndex() throws IOException {
        Set<Path> indexed = linkTargetsOf(INDEX);
        List<String> missing = new ArrayList<>();
        for (Path doc : markdownUnderDocs()) {
            if (!doc.equals(INDEX) && !indexed.contains(doc.normalize())) {
                missing.add(relative(doc));
            }
        }
        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(missing)),
                "these documents exist but docs/README.md does not list them, so nothing points at them and "
                        + "nobody will find them. Add a row saying what each is and how far to trust it.");
    }

    @Test
    void everyIndexEntryResolves() throws IOException {
        List<String> dangling = new ArrayList<>();
        for (Path target : linkTargetsOf(INDEX)) {
            if (!Files.exists(target)) {
                dangling.add(relative(target));
            }
        }
        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(dangling)),
                "docs/README.md links to these, and they are not there. A row pointing at nothing is worse than "
                        + "no row: it reads as an answer.");
    }

    @Test
    void livingDocumentsHaveNoBrokenRelativeLinks() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path doc : markdownUnderDocs()) {
            if (doc.startsWith(ARCHIVE)) {
                continue; // frozen on purpose -- see the class comment
            }
            for (Path target : linkTargetsOf(doc)) {
                if (!Files.exists(target)) {
                    broken.add(relative(doc) + " -> " + relative(target));
                }
            }
        }
        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(broken)),
                "these links in living documents do not resolve");
    }

    /**
     * Guards the guard. If {@link #LINK} stopped matching, or the walk stopped
     * finding files, every assertion above would pass by describing an empty
     * world -- the same failure mode {@code AccessContractCoverageTest} guards
     * against with its own parser check.
     */
    @Test
    void theIndexAndTheWalkAreActuallyBeingRead() throws IOException {
        assertTrue(Files.exists(INDEX), "docs/README.md does not exist; the index is the point of this test");
        int documents = markdownUnderDocs().size();
        int indexed = linkTargetsOf(INDEX).size();
        assertTrue(documents > 40, "only " + documents + " markdown files found under docs/; the walk is broken");
        assertTrue(indexed > 40, "only " + indexed + " links parsed out of docs/README.md; the parser is broken");
    }

    // --- helpers -------------------------------------------------------------

    private static List<Path> markdownUnderDocs() throws IOException {
        try (Stream<Path> walk = Files.walk(DOCS)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(Path::normalize)
                    .sorted()
                    .toList();
        }
    }

    /** Relative, non-external link targets of one markdown file, resolved to real paths. */
    private static Set<Path> linkTargetsOf(Path doc) throws IOException {
        Set<Path> targets = new LinkedHashSet<>();
        Matcher m = LINK.matcher(Files.readString(doc));
        while (m.find()) {
            String href = m.group(1);
            if (href.isBlank() || href.contains("://") || href.startsWith("mailto:")) {
                continue;
            }
            targets.add(doc.getParent().resolve(href).normalize());
        }
        return targets;
    }

    private static String relative(Path p) {
        return RepoRoot.root().relativize(p).toString().replace('\\', '/');
    }
}
