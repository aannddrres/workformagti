package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that stops SEC-06 from reopening.
 *
 * <p>The finding was not a bug in a check -- it was three checks that did not
 * exist. {@code users.manage}, {@code compliance.assign} and
 * {@code articles.view} were in the catalog, rendered as switches in the
 * admin UI, validated and persisted by
 * {@code PUT /api/users/{id}/permissions}, and consulted by nothing. An
 * administrator revoking {@code users.manage} was told it took effect; it did
 * not. Nothing in the codebase could notice, because "a permission nobody
 * asks about" leaves no failing test behind -- it just quietly does nothing.
 *
 * <p>So the invariant is asserted directly: every entry in the catalog must
 * be consulted by at least one {@code hasPermission(...)} call in production
 * code. Adding a permission without wiring it up now fails the build, and the
 * only ways to pass are to enforce it or to not ship the switch.
 *
 * <p>This reads source text rather than bytecode on purpose. A reflective
 * check would need every controller instantiated and every branch driven to
 * observe the call; the property being defended -- "somebody, somewhere,
 * asks about this permission" -- is a property of the source, and reading it
 * is both simpler and harder to fool accidentally.
 */
class PermissionEnforcementCoverageTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * Matches {@code hasPermission(<anything>, Permission.NAME)} across line
     * breaks. Anchored on the call rather than a bare {@code Permission.NAME}
     * mention so that naming a permission in a comment, a javadoc or a
     * default-role set does not count as enforcing it -- which is exactly the
     * mistake this test exists to catch.
     */
    private static Pattern enforcementOf(Permission permission) {
        return Pattern.compile("hasPermission\\s*\\([^;]*?Permission\\." + permission.name() + "\\b", Pattern.DOTALL);
    }

    private static String allProductionSource() throws IOException {
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(file)).append('\n');
            }
        }
        return combined.toString();
    }

    @Test
    void everyPermissionInTheCatalogIsActuallyEnforcedSomewhere() throws IOException {
        String source = allProductionSource();

        List<String> unenforced = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (!enforcementOf(permission).matcher(source).find()) {
                unenforced.add(permission.value());
            }
        }

        assertTrue(unenforced.isEmpty(),
                "These permissions are offered to administrators but checked nowhere: " + unenforced
                        + ". Either enforce them with a hasPermission(...) call, or remove them from the "
                        + "catalog -- a switch that does nothing is worse than no switch, because it "
                        + "tells an administrator they have a control they do not have (audit SEC-06).");
    }

    /**
     * Pins the removal itself. Re-adding {@code articles.view} would put back
     * a switch that cannot safely be made real: OPERATOR holds no permissions
     * at all, so enforcing it would close the knowledge base to every person
     * the product exists for.
     */
    @Test
    void articlesViewIsNotBackInTheCatalog() {
        boolean present = Stream.of(Permission.values()).anyMatch(p -> p.value().equals("articles.view"));

        assertTrue(!present,
                "articles.view was removed by SEC-06 -- see Permission.java's comment before re-adding it");
    }

    /** Sanity check on the test itself: the regex must not match a permission that genuinely is not enforced. */
    @Test
    void theDetectionWouldActuallyFailForAnUnenforcedPermission() throws IOException {
        String source = allProductionSource();
        Pattern neverPresent = Pattern.compile("hasPermission\\s*\\([^;]*?Permission\\.NO_SUCH_PERMISSION\\b", Pattern.DOTALL);

        assertTrue(!neverPresent.matcher(source).find(),
                "the matcher used above is too loose -- it found a permission that does not exist");
    }
}
