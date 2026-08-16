package ge.magti.portal.security;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-13. The finding was that five read paths matched a manager's
 * department by string equality, so a manager stored as the bare parent saw
 * an empty team. The fix widens access, which is the part worth pinning:
 * these assert both that the intended widening happens AND that the
 * asymmetry bug #312 relies on is untouched.
 */
class ManagerScopeTest {

    private static User user(String name, Role role, String department) {
        User u = new User();
        u.setId((long) Math.abs(name.hashCode()));
        u.setName(name);
        u.setRole(role);
        u.setDepartment(department);
        return u;
    }

    private static final User GROUP_01 = user("ჯგუფი 01 ოპერატორი", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
    private static final User GROUP_03 = user("ჯგუფი 03 ოპერატორი", Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
    private static final User BARE_PARENT = user("პარენტ ოპერატორი", Role.OPERATOR, "ტექნიკური");
    private static final User OTHER_DEPT = user("ოფისის ოპერატორი", Role.OPERATOR, "ოფისი — ჯგუფი 01");
    private static final User NO_DEPT = user("უდეპარტამენტო", Role.OPERATOR, null);
    private static final User ALL_DEPT = user("ოლ ოპერატორი", Role.OPERATOR, "All");

    private static final List<User> ACTIVE =
            List.of(GROUP_01, GROUP_03, BARE_PARENT, OTHER_DEPT, NO_DEPT, ALL_DEPT);

    private static List<String> namesVisibleTo(User manager) {
        return ManagerScope.visibleActiveUsers(ACTIVE, manager).stream().map(User::getName).toList();
    }

    /** The finding itself: this manager used to see nobody. */
    @Test
    void parentDepartmentManagerSeesTheWholeSubtree() {
        List<String> visible = namesVisibleTo(user("პარენტ მენეჯერი", Role.MANAGER, "ტექნიკური"));

        assertTrue(visible.contains("ჯგუფი 01 ოპერატორი"));
        assertTrue(visible.contains("ჯგუფი 03 ოპერატორი"));
        assertTrue(visible.contains("პარენტ ოპერატორი"), "the parent's own direct reports too");
        assertFalse(visible.contains("ოფისის ოპერატორი"), "but not another department");
    }

    /**
     * The other half, and the reason this could not just be "match on the
     * prefix": bug #312 was a sub-group manager seeing a sibling group's
     * overdue operator. That must stay fixed.
     */
    @Test
    void subGroupManagerSeesOnlyTheirOwnGroup() {
        List<String> visible = namesVisibleTo(user("ჯგუფის მენეჯერი", Role.MANAGER, "ტექნიკური — ჯგუფი 03"));

        assertEquals(List.of("ჯგუფი 03 ოპერატორი"), visible);
    }

    /**
     * An unassigned manager resolves to nobody. Under exact match this was
     * accidentally true here and accidentally FALSE in the audit log, where
     * a null department returned null and null meant unrestricted.
     */
    @Test
    void managerWithNoDepartmentSeesNobody() {
        assertTrue(namesVisibleTo(user("უსკოპო", Role.MANAGER, null)).isEmpty());
        assertTrue(namesVisibleTo(user("ცარიელი", Role.MANAGER, "  ")).isEmpty());
    }

    /**
     * "All" is a wildcard when a piece of content targets it. Handing a
     * caller's own department to DepartmentMatcher as a target would
     * therefore promote this manager to an org-wide reader of everyone's
     * compliance data -- SEC-02/SEC-03 through a data value. Matched by
     * equality instead, which is what the exact-match code it replaces did,
     * so this manager sees the other "All" users and nobody else.
     */
    @Test
    void managerStoredAsAllSeesOnlyOtherAllUsersNotEveryone() {
        List<String> visible = namesVisibleTo(user("ოლ მენეჯერი", Role.MANAGER, "All"));

        assertEquals(List.of("ოლ ოპერატორი"), visible);
    }

    /** Whitespace and dash spelling are normalised, as everywhere else. */
    @Test
    void matchingToleratesDashAndSpacingVariants() {
        User manager = user("მენეჯერი", Role.MANAGER, "  ტექნიკური  ");

        assertTrue(namesVisibleTo(manager).contains("ჯგუფი 03 ოპერატორი"));
    }

    @Test
    void onlyManagersAreDepartmentScoped() {
        assertTrue(ManagerScope.isDepartmentScoped(user("მ", Role.MANAGER, "ტექნიკური")));
        assertFalse(ManagerScope.isDepartmentScoped(user("ა", Role.SYSTEM_ADMIN, "ტექნიკური")));
        assertFalse(ManagerScope.isDepartmentScoped(user("კ", Role.CONTENT_ADMIN, "ტექნიკური")));
        assertFalse(ManagerScope.isDepartmentScoped(null));
    }
}
