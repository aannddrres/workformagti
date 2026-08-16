package ge.magti.portal.security;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;

/**
 * The one rule for "which users may a manager see" (audit SEC-13).
 *
 * <h2>What was wrong</h2>
 *
 * Five places independently answered this question with
 * {@code findByActiveTrueAndDepartment(user.getDepartment())} -- exact
 * string equality. Departments in this system are free text with an
 * organisational hierarchy encoded in the string itself: operators sit in
 * {@code "ტექნიკური — ჯგუფი 03"} while the manager over them may be stored
 * as the bare parent {@code "ტექნიკური"}. Exact equality matches nobody, so
 * that manager saw an empty team list, an empty critical-operators ribbon,
 * an empty audit log and an empty export -- silently, as "you have no
 * team", not as an error.
 *
 * <p>The codebase already had the right rule and was already using it for
 * content visibility, compliance eligibility, required-reading notification
 * and direct messaging: {@link DepartmentMatcher#matches}, whose javadoc and
 * {@link ge.magti.portal.messaging.DirectMessagePermission}'s both document
 * this exact parent/child situation as real. Only the read-scoping paths had
 * been left on string equality.
 *
 * <h2>The direction of the match, and why it is safe to widen</h2>
 *
 * {@code matches(candidateDepartment, List.of(managerDepartment))} is
 * deliberately asymmetric, the same way messaging is:
 *
 * <ul>
 *   <li>a manager at {@code "ტექნიკური"} matches every
 *       {@code "ტექნიკური — ჯგუფი NN"} beneath them -- this is the fix;
 *   <li>a manager at {@code "ტექნიკური — ჯგუფი 03"} matches only their own
 *       group, never a sibling and never the bare parent -- unchanged from
 *       the exact-match behaviour, which is why bug #312's fix is not
 *       loosened by this.
 * </ul>
 *
 * So the widening is exactly "a parent manager can see their own subtree",
 * and nothing else moves.
 *
 * <h2>Two ways to fail open, both closed here on purpose</h2>
 *
 * <ol>
 *   <li><b>A blank department.</b> {@code users.department} is nullable
 *       ({@code V3__create_users.sql:13}). Under exact match a manager with
 *       no department resolved to zero users -- accidentally fail-closed.
 *       {@link #visibleActiveUsers} keeps that, explicitly.
 *   <li><b>The literal string {@code "All"}.</b>
 *       {@code DepartmentMatcher.matches} treats an {@code "All"} <i>target</i>
 *       as a wildcard, because that is what it means on the content side: an
 *       article targeted at "All" is for everyone. Passing a caller's own
 *       department through as a target would therefore promote a manager
 *       stored as "All" to an org-wide reader of everyone's personal
 *       compliance data -- SEC-02 and SEC-03 arriving through a data value
 *       instead of through missing code. Matched by plain equality instead,
 *       which is also what the exact-match implementations did before this
 *       class existed, so nobody's reach changes.
 * </ol>
 *
 * <p>Note the deliberate difference from
 * {@link ge.magti.portal.messaging.DirectMessagePermission#canSend}, which
 * keeps "All" as a wildcard. Messaging is about who you may talk to and has
 * treated "All" that way since it was written; this class is about whose
 * personal data you may read, where an ambiguous value must not mean
 * "everyone's". The null case is fail-closed in both -- that one was a real
 * fail-open in messaging, where a null sender department was mapped to the
 * "All" wildcard and let an unassigned manager message the whole company.
 */
public final class ManagerScope {

    /** The department string {@link DepartmentMatcher} treats as a wildcard. */
    private static final String WILDCARD = "All";

    private ManagerScope() {
    }

    /** True when this caller's reads must be narrowed to their own department. */
    public static boolean isDepartmentScoped(User caller) {
        return caller != null && caller.getRole() == Role.MANAGER;
    }

    /**
     * Filters {@code activeUsers} down to the ones {@code manager} may see.
     *
     * <p>Takes the candidate list rather than a repository so the caller
     * keeps control of the query it already had to run, and so this stays
     * DB-free and directly unit-testable. ~600 active users makes the
     * in-Java filter free; doing it in SQL is not an option anyway, because
     * the rule normalises dashes and whitespace before comparing
     * ({@link DepartmentMatcher#splitGroup}) and a {@code LIKE 'x%'} would
     * silently disagree with every other department check in the codebase.
     */
    public static List<User> visibleActiveUsers(List<User> activeUsers, User manager) {
        String department = manager == null ? null : manager.getDepartment();
        if (department == null || department.isBlank()) {
            return List.of();
        }
        // DepartmentMatcher normalises the candidate side but compares the
        // target verbatim, so an untrimmed "  ტექნიკური " would match nobody
        // -- the same silent empty screen SEC-13 is about, arriving by a
        // different route. Normalised here, on the one value that comes
        // from a free-text admin form.
        String target = DepartmentMatcher.normalize(department);

        if (WILDCARD.equals(target)) {
            // Plain equality, deliberately NOT DepartmentMatcher: it would
            // read this as the wildcard and hand the caller the whole
            // company, which is SEC-02/SEC-03 arriving through a data value
            // instead of through missing code. Equality is also exactly what
            // the exact-match implementations did before SEC-13, so a manager
            // stored as "All" keeps the reach they already had -- neither
            // widened to everyone nor narrowed to nobody.
            return activeUsers.stream()
                    .filter(candidate -> WILDCARD.equals(candidate.getDepartment()))
                    .toList();
        }

        List<String> targets = List.of(target);
        return activeUsers.stream()
                .filter(candidate -> DepartmentMatcher.matches(candidate.getDepartment(), targets))
                .toList();
    }
}
