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
    void systemAuditMergesTheColonNamedCatalogEntry() {
        assertEquals("system.audit", Permission.SYSTEM_AUDIT.value());
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
    void contentAdminDefaultsMatchSecurityPy() {
        assertEquals(
                Set.of(Permission.ARTICLES_VIEW, Permission.ARTICLES_EDIT, Permission.ARTICLES_PUBLISH,
                        Permission.ARTICLES_ARCHIVE, Permission.VIDEOS_ARCHIVE, Permission.COMPLIANCE_ASSIGN),
                Permission.defaultsFor(Role.CONTENT_ADMIN));
    }
}
