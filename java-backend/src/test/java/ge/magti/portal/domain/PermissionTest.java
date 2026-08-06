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

    /**
     * REPORTS_EXPORT matches security.py's DEFAULT_PERMISSIONS_BY_ROLE
     * directly; SYSTEM_AUDIT does not come from that dict at all -- Python
     * grants it to managers through the separate, retired colon-named RBAC
     * catalog instead (migrate.py's ensure_system_audit_permission_seeded).
     * Confirmed missing here via a live parity check against the running
     * Python app (2026-08-06) and added back deliberately, not part of the
     * original "field-for-field" dict mirror.
     */
    @Test
    void managerDefaultsToReportsExportAndSystemAudit() {
        assertEquals(Set.of(Permission.REPORTS_EXPORT, Permission.SYSTEM_AUDIT), Permission.defaultsFor(Role.MANAGER));
    }

    /** See managerDefaultsToReportsExportAndSystemAudit's javadoc -- same SYSTEM_AUDIT addition applies here. */
    @Test
    void contentAdminDefaultsMatchSecurityPyPlusSystemAudit() {
        assertEquals(
                Set.of(Permission.ARTICLES_VIEW, Permission.ARTICLES_EDIT, Permission.ARTICLES_PUBLISH,
                        Permission.ARTICLES_ARCHIVE, Permission.VIDEOS_ARCHIVE, Permission.COMPLIANCE_ASSIGN,
                        Permission.SYSTEM_AUDIT),
                Permission.defaultsFor(Role.CONTENT_ADMIN));
    }
}
