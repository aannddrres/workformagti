package ge.magti.portal.docs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root from wherever the suite happens to be running.
 *
 * <p>Surefire starts in {@code java-backend/}, but the same tests are run from
 * the repository root by {@code scripts/verify-like-ci.sh} and from an IDE with
 * either as the working directory. The existing document-reading tests each
 * carry their own two-element candidate list; the tests in this package read
 * several files apiece, so they share one resolver instead.
 *
 * <p>The marker is {@code AGENTS.md} beside a {@code docs/} directory —
 * together specific enough that no subdirectory can be mistaken for the root.
 */
final class RepoRoot {

    private static final Path ROOT = locate();

    private RepoRoot() {
    }

    static Path path(String relative) {
        return ROOT.resolve(relative);
    }

    static Path root() {
        return ROOT;
    }

    private static Path locate() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("AGENTS.md")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
        }
        throw new AssertionError(
                "repository root not found: walked up from " + start + " looking for AGENTS.md beside docs/");
    }
}
