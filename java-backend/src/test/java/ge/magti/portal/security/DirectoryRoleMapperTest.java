package ge.magti.portal.security;

import ge.magti.portal.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryRoleMapperTest {

    private static final String PROPOSED =
            "INFOPORTAL_ADMIN=admin,INFOPORTAL_CONTENT_ADMIN=content_admin,"
                    + "INFOPORTAL_MANAGER=manager,INFOPORTAL_OPERATOR=operator";

    private final DirectoryRoleMapper mapper = new DirectoryRoleMapper(PROPOSED);

    @Test
    void eachMappedAuthorityGivesItsRole() {
        assertEquals(Role.SYSTEM_ADMIN, mapper.roleFor(List.of("INFOPORTAL_ADMIN")));
        assertEquals(Role.CONTENT_ADMIN, mapper.roleFor(List.of("INFOPORTAL_CONTENT_ADMIN")));
        assertEquals(Role.MANAGER, mapper.roleFor(List.of("INFOPORTAL_MANAGER")));
        assertEquals(Role.OPERATOR, mapper.roleFor(List.of("INFOPORTAL_OPERATOR")));
    }

    /**
     * Every employee's token today carries dozens of authorities from other
     * internal systems. None of them may leak into a portal role.
     */
    @Test
    void authoritiesOfOtherSystemsAreIgnoredAndNoneMappedMeansOperator() {
        assertEquals(Role.OPERATOR, mapper.roleFor(List.of("MAGTICOM_USER", "LIST_ALL_PAYMENTS")));
        assertEquals(Role.OPERATOR, mapper.roleFor(List.of()));
    }

    @Test
    void twoMappedRolesResolveToTheHigher() {
        assertEquals(Role.SYSTEM_ADMIN, mapper.roleFor(List.of("INFOPORTAL_MANAGER", "INFOPORTAL_ADMIN")));
        assertEquals(Role.CONTENT_ADMIN, mapper.roleFor(List.of("INFOPORTAL_MANAGER", "INFOPORTAL_CONTENT_ADMIN")));
        assertEquals(Set.of(Role.MANAGER, Role.CONTENT_ADMIN),
                mapper.mappedRoles(List.of("INFOPORTAL_MANAGER", "INFOPORTAL_CONTENT_ADMIN")));
    }

    /**
     * A typo in the deployment must stop the boot. Silently skipping the bad
     * entry would demote whoever it named at their next sign-in.
     */
    @Test
    void aMalformedMapIsRefusedRatherThanPartlyApplied() {
        assertThrows(IllegalArgumentException.class, () -> DirectoryRoleMapper.validate("INFOPORTAL_ADMIN=superuser"));
        assertThrows(IllegalArgumentException.class, () -> DirectoryRoleMapper.validate("INFOPORTAL_ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> DirectoryRoleMapper.validate("=admin"));
        assertDoesNotThrow(() -> DirectoryRoleMapper.validate(PROPOSED + ", "));
    }
}
