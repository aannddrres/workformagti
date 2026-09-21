package ge.magti.portal.web;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.security.PermissionChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * The two denial checks that every controller needed and every controller
 * wrote out again.
 *
 * <h2>Why this is not just tidying</h2>
 *
 * {@code SecurityConfig} only authenticates -- its chain is deny-by-default,
 * {@code .anyRequest().authenticated()} -- and there is not one
 * {@code @PreAuthorize} in this module: role, permission and scope checks
 * happen in handler bodies, by calling a {@code require*} helper. That design
 * is deliberate and is enforced by {@code EndpointGuardCoverageTest}, which
 * reads bytecode and fails the build for any endpoint whose call closure
 * reaches no guard.
 *
 * <p>What it did not pin was <b>how many</b> copies of each guard existed.
 * {@code requireAuthenticated} was written into 16 controllers and
 * {@code requireContentManage} into 7 — 23 copies of two rules. Every copy is
 * a chance for one of them to be written slightly differently, and one already
 * was: {@code requireSystemAdmin}, the third of the family, exists nine times
 * and returns <b>four different messages</b>, one of which
 * ({@code AuditLogController}'s) is pinned by four tests.
 *
 * <p>So a change to "who counts as authenticated" is a 16-file edit today.
 * That is the cost this class removes.
 *
 * <h2>Why requireSystemAdmin is not here</h2>
 *
 * Deliberately left where it is, and this is the interesting half. Its nine
 * copies are not interchangeable:
 *
 * <ul>
 *   <li>four different 403 bodies — the English "Not enough permissions to
 *       perform this action" in five controllers, and three separate Georgian
 *       sentences in {@code AuditLogController},
 *       {@code AdminExportController} and {@code ContentTrashController};
 *   <li>{@code AdminExportController} additionally requires
 *       {@code user.isActive()}. That is defence in depth rather than a hole
 *       the others have — {@code JwtAuthenticationFilter:102} already refuses
 *       a deactivated caller on every request — but it is still a real
 *       difference between two methods with one name.
 * </ul>
 *
 * <p>Merging them would change response bodies on three controllers, and what
 * a denied caller is told is a product decision, not a refactor. Unifying
 * those messages is worth doing; it belongs in a commit that says so, with the
 * four tests updated in the same breath rather than edited into agreement
 * afterwards. {@code ControllerGuardConsolidationTest} keeps the inventory so
 * the choice stays visible instead of being rediscovered.
 *
 * <h2>Shape</h2>
 *
 * Both return {@code null} to mean "carry on" and a populated
 * {@link ResponseEntity} to mean "stop and send this", which is the calling
 * convention every controller here already used. Static, and taking the
 * {@link PermissionChecker} as an argument rather than holding one, so this
 * class has no lifecycle and nothing to mock.
 */
public final class Guards {

    /** Kept byte-for-byte: this string is what the frontend's 401 handling matches on. */
    private static final String NOT_AUTHENTICATED = "Could not validate credentials";

    private static final String INSUFFICIENT_PERMISSIONS = "წვდომა უარყოფილია: არასაკმარისი უფლებები";

    private Guards() {
    }

    /**
     * The floor. The filter chain already turns away a request with no usable
     * token before it reaches a protected handler, so this is the second line,
     * not the first: it keeps a handler a 401 rather than an open door if its
     * path is ever added to one of SecurityConfig's {@code ANONYMOUS_*}
     * arrays. Every handler that touches anything but public content still
     * starts here.
     */
    public static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", NOT_AUTHENTICATED));
        }
        return null;
    }

    /**
     * {@code content.manage}: company-wide publishing, other people's
     * articles, and the category tree — one capability on purpose (rule #9).
     * Splitting it into finer switches would produce controls that always move
     * together, which tells an administrator they have a choice they do not.
     *
     * <p>Says nothing about whose personal data may be read. A content
     * capability never widens scope over employees; that comes from a
     * leadership assignment or from {@code SYSTEM_ADMIN}.
     */
    public static ResponseEntity<Map<String, String>> requireContentManage(
            User user, PermissionChecker permissionChecker) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", INSUFFICIENT_PERMISSIONS));
        }
        return null;
    }
}
