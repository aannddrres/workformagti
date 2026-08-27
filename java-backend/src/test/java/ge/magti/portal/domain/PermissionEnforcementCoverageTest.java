package ge.magti.portal.domain;

import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.web.ControllerBytecode;
import ge.magti.portal.web.ControllerEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Each production source file on its own, never concatenated.
     *
     * <p>The joined-blob version this replaces had a false positive in the
     * one direction that matters: {@link #enforcementOf}'s {@code [^;]*?}
     * can span a file boundary, so a {@code hasPermission(user,} left
     * dangling at the end of one file and a {@code Permission.SYSTEM_AUDIT}
     * near the start of the next would together read as an enforcement that
     * exists in neither. A test whose failure mode is "wrongly reports the
     * permission IS enforced" defeats its own purpose, so the possibility is
     * removed rather than argued about.
     */
    private static List<String> productionSourceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<String> contents = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                contents.add(Files.readString(file));
            }
            return contents;
        }
    }

    private static boolean anyFileMatches(List<String> sources, Pattern pattern) {
        return sources.stream().anyMatch(source -> pattern.matcher(source).find());
    }

    @Test
    void everyPermissionInTheCatalogIsActuallyEnforcedSomewhere() throws IOException {
        List<String> sources = productionSourceFiles();

        List<String> unenforced = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (!anyFileMatches(sources, enforcementOf(permission))) {
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
     * One of the two ways a permission can be checked and still never come
     * back false: every role that could be asked already holds it.
     *
     * <p>{@code hasPermission} answers true for SYSTEM_ADMIN before looking
     * at anything, so a permission granted by default to all three remaining
     * roles is true for everyone the system creates. It renders as a switch,
     * saves, persists, is genuinely consulted -- and no request is ever
     * refused because of it. The sibling test above passes such a permission
     * happily, because somebody does call {@code hasPermission} with it.
     *
     * <p>Judged against {@link Permission#defaultsFor} rather than a
     * hand-made user, because the defaults are what a real account actually
     * gets on creation and on a role change ({@code UserController:213},
     * {@code :450}, {@code AuthenticationService:114}): the question is
     * whether the permission can be denied to somebody who exists, not
     * merely to a {@link User} a test could construct.
     *
     * <p>The <i>other</i> way -- a permission only ever consulted where
     * SYSTEM_ADMIN is already required, which is the shape
     * {@code users.manage} actually had -- is a property of the call sites,
     * not of the defaults, and this test cannot see it. That one is
     * {@link #noPermissionIsConsultedOnlyBehindTheSystemAdminBypass}.
     */
    @Test
    void everyPermissionCanActuallyRefuseSomebody() {
        PermissionChecker checker = new PermissionChecker();

        List<String> unrefusable = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            boolean refusableSomewhere = Stream.of(Role.values())
                    .filter(role -> role != Role.SYSTEM_ADMIN)
                    .anyMatch(role -> !checker.hasPermission(userWithDefaultsFor(role), permission));
            if (!refusableSomewhere) {
                unrefusable.add(permission.value());
            }
        }

        assertTrue(unrefusable.isEmpty(),
                "These permissions can never be false for anyone: " + unrefusable
                        + ". PermissionChecker short-circuits true for SYSTEM_ADMIN, so a permission every "
                        + "other role holds by default -- or that only SYSTEM_ADMIN holds -- is structurally "
                        + "incapable of refusing a request, however many hasPermission calls name it. Either "
                        + "give a non-admin role a reason to be denied it, or do not ship the switch "
                        + "(audit SEC-06, and PermissionChecker's javadoc).");
    }

    /**
     * The way {@code users.manage} was actually dead, and the one
     * {@link PermissionChecker}'s javadoc says nothing could catch: a
     * permission consulted only at endpoints that already require the
     * SYSTEM_ADMIN role.
     *
     * <p>SYSTEM_ADMIN bypasses {@code hasPermission} unconditionally. So a
     * check reachable only by a SYSTEM_ADMIN is evaluated exclusively for the
     * one role that skips the evaluation -- the switch is validated,
     * persisted and consulted, and still cannot change any outcome. Revoking
     * it told the administrator it took effect; it did not. Neither of the
     * source-text tests above can see this, because both are satisfied: the
     * permission is in the catalog and something does call
     * {@code hasPermission} with it.
     *
     * <p>Answering it means reading method bodies, so this is the one test
     * here that uses bytecode rather than source text (see
     * {@link ControllerBytecode} for what that can and cannot see). For each
     * permission it finds the endpoints whose call closure both names the
     * constant and reaches a {@code hasPermission} call, then asks whether
     * every one of them also calls {@code requireSystemAdmin}. If so, the
     * permission is structurally incapable of refusing anybody.
     *
     * <p>A permission consulted nowhere at all is not this test's business --
     * {@link #everyPermissionInTheCatalogIsActuallyEnforcedSomewhere} already
     * fails for that, and reporting it twice would just make one fix look
     * like two problems.
     */
    @Test
    void noPermissionIsConsultedOnlyBehindTheSystemAdminBypass() {
        List<Class<?>> controllers = ControllerEndpoints.restControllers();
        List<String> deadBehindTheBypass = new ArrayList<>();
        boolean sawAnAdminGate = false;

        for (Permission permission : Permission.values()) {
            int consultingEndpoints = 0;
            int behindTheBypass = 0;

            for (Class<?> controller : controllers) {
                Map<String, ControllerBytecode.Body> bodies = ControllerBytecode.read(controller);
                for (Method method : controller.getDeclaredMethods()) {
                    RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                    if (mapping == null) {
                        continue;
                    }
                    ControllerBytecode.Body closure = ControllerBytecode.closureOf(controller, method, bodies);
                    boolean namesIt = closure.staticFieldsRead().contains(PERMISSION_OWNER + "." + permission.name());
                    boolean checksSomething = closure.invocations().stream()
                            .anyMatch(invocation -> invocation.name().equals("hasPermission"));
                    if (!namesIt || !checksSomething) {
                        continue;
                    }
                    consultingEndpoints++;
                    boolean adminGated = closure.invocations().stream()
                            .anyMatch(invocation -> invocation.name().equals(SYSTEM_ADMIN_GATE));
                    sawAnAdminGate |= adminGated;
                    if (adminGated) {
                        behindTheBypass++;
                    }
                }
            }

            if (consultingEndpoints > 0 && consultingEndpoints == behindTheBypass) {
                deadBehindTheBypass.add(permission.value());
            }
        }

        assertTrue(sawAnAdminGate || anyEndpointCalls(controllers, SYSTEM_ADMIN_GATE),
                "no endpoint anywhere was seen calling " + SYSTEM_ADMIN_GATE + " -- the detector cannot "
                        + "recognise the admin gate, so this test would never fire whatever the code did");
        assertTrue(deadBehindTheBypass.isEmpty(),
                "These permissions are only ever consulted at endpoints that already require the "
                        + "SYSTEM_ADMIN role: " + deadBehindTheBypass + ". PermissionChecker returns true for "
                        + "SYSTEM_ADMIN before reading anything, so the check runs solely for the role that "
                        + "skips it and can never refuse a request -- exactly the shape users.manage had when "
                        + "SEC-06 removed it rather than enforcing it. Either give a non-admin role an "
                        + "endpoint where the permission decides, or do not ship the switch.");
    }

    /** Internal name of the enum, as the constant pool spells it. */
    private static final String PERMISSION_OWNER = "ge/magti/portal/domain/Permission";

    /** The codebase's single SYSTEM_ADMIN role gate. */
    private static final String SYSTEM_ADMIN_GATE = "requireSystemAdmin";

    /**
     * Non-vacuity for the check above: it concludes "dead" from every
     * consulting endpoint being admin-gated, so a detector that could not
     * see the gate at all would quietly conclude the opposite forever.
     */
    private static boolean anyEndpointCalls(List<Class<?>> controllers, String calledName) {
        for (Class<?> controller : controllers) {
            Map<String, ControllerBytecode.Body> bodies = ControllerBytecode.read(controller);
            for (Method method : controller.getDeclaredMethods()) {
                if (ControllerEndpoints.mappingOf(method) == null) {
                    continue;
                }
                boolean found = ControllerBytecode.closureOf(controller, method, bodies).invocations().stream()
                        .anyMatch(invocation -> invocation.name().equals(calledName));
                if (found) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Negative control: the check above must reject a permission nobody can be refused. */
    @Test
    void theRefusabilityCheckWouldActuallyFailForAnAdminOnlyPermission() {
        PermissionChecker checker = new PermissionChecker();

        // Every non-admin role granted it, which is what "only SYSTEM_ADMIN
        // can be refused" looks like from the checker's side -- the shape
        // users.manage had.
        boolean refusableSomewhere = Stream.of(Role.values())
                .filter(role -> role != Role.SYSTEM_ADMIN)
                .anyMatch(role -> {
                    User user = new User();
                    user.setRole(role);
                    user.setPermissions(Set.of(Permission.COMPLIANCE_ASSIGN.value()));
                    return !checker.hasPermission(user, Permission.COMPLIANCE_ASSIGN);
                });

        assertTrue(!refusableSomewhere,
                "the refusability check is too loose -- it found a refusal where every role holds the "
                        + "permission, so it would not have caught users.manage either");
    }

    /**
     * {@link Permission#defaultsFor} is backed by a {@code Map.of} listing
     * the four roles that exist today, and it is dereferenced immediately at
     * all three call sites -- two {@code for}-each loops and a
     * {@code .stream()}. A fifth {@link Role} would therefore not degrade
     * gracefully: it would NPE inside user creation, role reassignment and
     * JIT provisioning, at runtime, on the first account that used it.
     */
    @Test
    void everyRoleHasADefaultPermissionSet() {
        List<Role> missing = Stream.of(Role.values())
                .filter(role -> Permission.defaultsFor(role) == null)
                .toList();

        assertEquals(List.of(), missing,
                "these roles have no entry in Permission.DEFAULTS_BY_ROLE, and every caller dereferences "
                        + "the result straight away -- adding a role without one NPEs user creation, role "
                        + "reassignment and JIT provisioning rather than failing visibly here");
    }

    /**
     * Makes the open decision in {@link PermissionChecker}'s javadoc -- drop
     * the SYSTEM_ADMIN bypass so permissions bind for admins too -- safe to
     * take later.
     *
     * <p>Today SYSTEM_ADMIN's default set is never consulted: the bypass
     * answers first. That is precisely why it can drift without anyone
     * noticing, and why the day the bypass is removed is the day the drift
     * turns into administrators silently losing abilities on deploy --
     * the consequence that javadoc names as the reason the change needs a
     * look at real data first. Keeping the set complete now means the
     * decision, whenever it is taken, changes only the code it is about.
     */
    @Test
    void systemAdminDefaultsCoverTheWholeCatalog() {
        Set<Permission> adminDefaults = Permission.defaultsFor(Role.SYSTEM_ADMIN);
        List<String> absent = Stream.of(Permission.values())
                .filter(permission -> !adminDefaults.contains(permission))
                .map(Permission::value)
                .sorted()
                .toList();

        assertEquals(List.of(), absent,
                "SYSTEM_ADMIN's default permission set is missing " + absent + ". The bypass hides this "
                        + "today, so nothing fails -- until the bypass is dropped, and then every admin "
                        + "account loses those abilities at deploy time with no error to point at.");
    }

    /** A user as the system would actually create one for this role. */
    private static User userWithDefaultsFor(Role role) {
        User user = new User();
        user.setRole(role);
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return user;
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
        List<String> sources = productionSourceFiles();
        Pattern neverPresent = Pattern.compile("hasPermission\\s*\\([^;]*?Permission\\.NO_SUCH_PERMISSION\\b", Pattern.DOTALL);

        assertTrue(!anyFileMatches(sources, neverPresent),
                "the matcher used above is too loose -- it found a permission that does not exist");
    }
}
