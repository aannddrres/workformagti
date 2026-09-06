package ge.magti.portal.web;

import ge.magti.portal.docs.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the shared denial checks shared.
 *
 * <h2>What this is for</h2>
 *
 * Authorization in this module happens in handler bodies — {@code SecurityConfig}
 * is {@code anyRequest().permitAll()} and there is no {@code @PreAuthorize}
 * anywhere — so a {@code require*} helper is the gate.
 * {@link EndpointGuardCoverageTest} already fails the build for an endpoint
 * that reaches none. What nothing pinned was how many copies of each helper
 * existed: {@code requireAuthenticated} had been written into 16 controllers
 * and {@code requireContentManage} into 7. Twenty-three copies of two rules,
 * so changing "who counts as authenticated" was a 16-file edit, and each copy
 * was a chance for one of them to drift.
 *
 * <p>They are in {@link Guards} now. This test is what stops the eighteenth
 * copy from being written, since re-declaring the helper locally is the
 * natural thing to do and nothing else would notice.
 *
 * <h2>The one that is not consolidated, and why the inventory is here</h2>
 *
 * {@code requireSystemAdmin} stayed put. Its nine copies return <b>four
 * different</b> 403 bodies, three of them Georgian sentences unique to one
 * controller, and one of those is asserted by four tests. Merging them would
 * change what a denied caller is told on three controllers, which is a product
 * decision rather than a refactor.
 *
 * <p>{@link #theSystemAdminGuardsInventoryIsUnchanged} pins that inventory
 * instead. It is not a rule that the divergence must persist — it is a
 * tripwire, so that adding a tenth copy, or quietly changing one of the
 * messages, is a decision someone takes on purpose. Unifying them is a good
 * commit to write; this test is what makes it a visible one.
 */
class ControllerGuardConsolidationTest {

    private static final Path CONTROLLERS = RepoRoot.path("java-backend/src/main/java/ge/magti/portal/web");

    /** A local re-declaration of a guard that now lives in {@link Guards}. */
    private static final Pattern LOCAL_SHARED_GUARD = Pattern.compile(
            "private\\s+(?:static\\s+)?ResponseEntity<[^>]*>+\\s+"
                    + "(requireAuthenticated|requireContentManage)\\s*\\(");

    private static final Pattern SYSTEM_ADMIN_GUARD = Pattern.compile(
            "private\\s+(?:static\\s+)?ResponseEntity<[^>]*>+\\s+requireSystemAdmin\\s*\\(");

    @Test
    void noControllerDeclaresItsOwnCopyOfASharedGuard() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path controller : controllerSources()) {
            if (controller.getFileName().toString().equals("Guards.java")) {
                continue;
            }
            Matcher m = LOCAL_SHARED_GUARD.matcher(Files.readString(controller));
            while (m.find()) {
                offenders.add(controller.getFileName() + " declares its own " + m.group(1));
            }
        }
        assertEquals(List.of(), offenders.stream().sorted().toList(),
                "these guards live in Guards so that changing one of them is one edit rather than "
                        + "sixteen. Call Guards.requireAuthenticated(user) / "
                        + "Guards.requireContentManage(user, permissionChecker) instead of re-declaring. "
                        + "If the endpoint genuinely needs different behaviour, that is a differently "
                        + "named guard, not a second copy of this one.");
    }

    /**
     * The tripwire described in the class comment. Failing here is not
     * automatically a bug — it means the shape of the divergence changed, and
     * the fix is to update this list in the same commit that changes it, with
     * the reason.
     */
    @Test
    void theSystemAdminGuardsInventoryIsUnchanged() throws IOException {
        Map<String, Set<String>> messagesByController = new LinkedHashMap<>();
        for (Path controller : controllerSources()) {
            String source = Files.readString(controller);
            Matcher declaration = SYSTEM_ADMIN_GUARD.matcher(source);
            if (!declaration.find()) {
                continue;
            }
            Set<String> forbidden = new LinkedHashSet<>();
            Matcher body = Pattern.compile("\"([^\"]{10,})\"").matcher(
                    source.substring(declaration.start(), endOfMethod(source, declaration.start())));
            while (body.find()) {
                if (!"Could not validate credentials".equals(body.group(1))) {
                    forbidden.add(body.group(1));
                }
            }
            messagesByController.put(controller.getFileName().toString().replace(".java", ""), forbidden);
        }

        assertEquals(9, messagesByController.size(),
                "the number of requireSystemAdmin copies changed: " + messagesByController.keySet()
                        + ". A tenth copy is worth a moment's thought; so is one fewer.");

        Set<String> distinct = new LinkedHashSet<>();
        messagesByController.values().forEach(distinct::addAll);
        assertEquals(
                Set.of("Not enough permissions to perform this action",
                        "ეს ფუნქცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის",
                        "წვდომა უარყოფილია: მხოლოდ სისტემური ადმინისტრატორისთვის",
                        "წვდომა უარყოფილია: საჭიროა სისტემური ადმინისტრატორი"),
                distinct,
                "the set of things a system-admin-only endpoint tells a denied caller changed. Four "
                        + "wordings for one refusal is already one decision nobody took; changing them "
                        + "is fine, doing it without noticing is what this catches. "
                        + "AuditLogController's wording is asserted by four tests.");
    }

    /** Guards the guard: both assertions above scan a directory. */
    @Test
    void theScanIsActuallyReadingTheControllers() throws IOException {
        List<Path> sources = controllerSources();
        assertTrue(sources.size() >= 20,
                "only " + sources.size() + " controller sources found under " + CONTROLLERS
                        + "; the scan is looking in the wrong place and would pass regardless");
        assertTrue(sources.stream().anyMatch(p -> p.getFileName().toString().equals("Guards.java")),
                "Guards.java is not where this test thinks it is, so the exclusion above is not "
                        + "excluding anything and the inventory may be reading nothing");
    }

    private static List<Path> controllerSources() throws IOException {
        try (Stream<Path> walk = Files.walk(CONTROLLERS)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static int endOfMethod(String source, int start) {
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return source.length();
    }
}
