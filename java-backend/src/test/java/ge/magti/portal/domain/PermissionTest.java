package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionTest {

    @Test
    void everyPermissionRoundTripsThroughItsValue() {
        for (Permission permission : Permission.values()) {
            assertEquals(permission, Permission.fromValue(permission.value()));
        }
    }

    @Test
    void operatorGetsNoDefaultPermissions() {
        assertTrue(Permission.defaultsFor(Role.OPERATOR).isEmpty());
    }

    @Test
    void managerDefaultsToReportsExportOnly() {
        assertEquals(Set.of(Permission.REPORTS_EXPORT), Permission.defaultsFor(Role.MANAGER));
    }

    @Test
    void contentAdminDefaultsMatchTheAuthoringContract() {
        assertEquals(
                // ARTICLES_VIEW removed from the catalog by SEC-06: it was
                // never enforced and could not safely be, since OPERATOR
                // holds no permissions at all and gating reads on it would
                // have closed the knowledge base to everyone who uses it.
                Set.of(Permission.ARTICLES_EDIT, Permission.ARTICLES_ARCHIVE,
                        Permission.VIDEOS_ARCHIVE, Permission.COMPLIANCE_ASSIGN, Permission.CONTENT_MANAGE),
                Permission.defaultsFor(Role.CONTENT_ADMIN));
    }

    @Test
    void statsViewIsAnExplicitGrantForEveryNonAdminRole() {
        assertTrue(!Permission.defaultsFor(Role.OPERATOR).contains(Permission.STATS_VIEW));
        assertTrue(!Permission.defaultsFor(Role.MANAGER).contains(Permission.STATS_VIEW));
        assertTrue(!Permission.defaultsFor(Role.CONTENT_ADMIN).contains(Permission.STATS_VIEW));
        assertTrue(Permission.defaultsFor(Role.SYSTEM_ADMIN).contains(Permission.STATS_VIEW));
    }
}
