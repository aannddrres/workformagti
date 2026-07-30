package ge.magti.portal.messaging;

import ge.magti.portal.domain.Role;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;

/**
 * Mirrors the department-confidentiality check in routers/messaging.py's
 * {@code send_message} (routers/messaging.py:213-222): a manager may only
 * message users in their own department; a system admin may message anyone.
 *
 * <p><b>Fixed here, not faithfully reproduced (user decision, 2026-07-30):
 * department matching is now prefix-aware,</b> same reasoning and same user
 * sign-off as {@link SseEventVisibility}. The Python original compares
 * {@code recipient.department != current_sender.department} with plain
 * string inequality, so a manager whose own department is the parent
 * "გაყიდვები" could never message an operator in the sub-group
 * "გაყიდვები — ჯგუფი 2", despite that operator organizationally sitting
 * under that same manager's department.
 *
 * <p>The match direction is deliberately asymmetric, matching how
 * {@link DepartmentMatcher} already treats every other department check in
 * this codebase: the <em>recipient's</em> department is checked against the
 * <em>sender's</em> department as the target, not the other way round. A
 * parent-department manager ("გაყიდვები") can reach any of its sub-groups;
 * a sub-group manager ("გაყიდვები — ჯგუფი 2") cannot reach the bare parent
 * or a sibling sub-group -- only their own.
 */
public final class DirectMessagePermission {

    private DirectMessagePermission() {
    }

    public static boolean canSend(Role senderRole, String senderDepartment, String recipientDepartment) {
        if (senderRole != Role.MANAGER) {
            return true;
        }
        return DepartmentMatcher.matches(
                recipientDepartment, List.of(senderDepartment == null ? "All" : senderDepartment));
    }
}
