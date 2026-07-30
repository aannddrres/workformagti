package ge.magti.portal.messaging;

import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;
import java.util.Objects;

/**
 * Mirrors the SSE delivery filter in routers/messaging.py's
 * {@code event_generator} (routers/messaging.py:123-132) -- decides whether
 * one live "new content" event gets pushed to one connected viewer.
 *
 * <p><b>Fixed here, not faithfully reproduced (user decision, 2026-07-30):
 * department matching is now prefix-aware.</b> The Python original compares
 * {@code target_department == user_dept} with plain string equality, so an
 * article published to the parent department "გაყიდვები" never notifies a
 * viewer whose own department is the sub-group "გაყიდვები — ჯგუფი 2" --
 * even though that same viewer already sees the article once they load the
 * page, since every *visibility* check in routers/articles.py goes through
 * the prefix-aware {@link DepartmentMatcher#matches}. This mismatch
 * (content visible on refresh, but no live pop-up) was presented to the
 * user concretely before changing it; the answer was to fix it in the Java
 * port rather than carry the inconsistency forward, since there is no live
 * Java system yet for this to be a breaking change to. Role matching is
 * left untouched -- roles are a closed set with no sub-group concept, so
 * plain equality was already correct there.
 */
public final class SseEventVisibility {

    private SseEventVisibility() {
    }

    /**
     * @param viewerIsAdmin    true for a content-admin/system-admin viewer --
     *                         mirrors Python's {@code is_admin}, which bypasses every filter below
     * @param targetDepartment the event's department target; {@code null} or "All" means unrestricted
     * @param targetRole       the event's role target; {@code null} or "All" means unrestricted
     * @param targetUserId     optional single-recipient targeting; {@code null} means unrestricted
     * @param viewerDepartment the connected viewer's own department
     * @param viewerRole       the connected viewer's own role, as the wire-value string (matches Python's raw comparison)
     * @param viewerId         the connected viewer's own user id
     */
    public static boolean isVisible(
            boolean viewerIsAdmin,
            String targetDepartment,
            String targetRole,
            Long targetUserId,
            String viewerDepartment,
            String viewerRole,
            Long viewerId) {
        if (viewerIsAdmin) {
            return true;
        }
        boolean deptMatch = DepartmentMatcher.matches(
                viewerDepartment, List.of(targetDepartment == null ? "All" : targetDepartment));
        boolean roleMatch = targetRole == null || "All".equals(targetRole) || Objects.equals(targetRole, viewerRole);
        boolean userMatch = targetUserId == null || Objects.equals(targetUserId, viewerId);
        return deptMatch && roleMatch && userMatch;
    }
}
