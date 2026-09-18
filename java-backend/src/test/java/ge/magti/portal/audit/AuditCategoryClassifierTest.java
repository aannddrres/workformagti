package ge.magti.portal.audit;

import ge.magti.portal.domain.AuditCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditCategoryClassifierTest {

    @Test
    void loginAndPasswordActionsAreSecurityRegardlessOfItemType() {
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("system", "LOGIN"));
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("user", "PASSWORD_RESET"));
    }

    @Test
    void updateStatusToPrefixIsSecurity() {
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("article", "UPDATE_STATUS_TO_PUBLISHED"));
        assertEquals(AuditCategory.SECURITY,
                AuditCategoryClassifier.classify("admin_export", "EXPORT_ADMIN_SEARCH_HISTORY"));
    }

    @Test
    void securityActionOutranksTheItemTypesOwnCategory() {
        // item_type "user" would normally map to USER, but CREATE_USER/
        // UPDATE_PERMISSIONS must win as SECURITY -- the Python function
        // checks security actions first, unconditionally.
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("user", "CREATE_USER"));
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("user", "UPDATE_PERMISSIONS"));
    }

    @Test
    void userActionsCategorizeAsUserEvenForContentItemTypes() {
        // "VIEW" an article is about the user's own activity, not a content change.
        assertEquals(AuditCategory.USER, AuditCategoryClassifier.classify("article", "VIEW"));
        assertEquals(AuditCategory.USER, AuditCategoryClassifier.classify("required_reading", "MARK_READ"));
    }

    @Test
    void contentItemTypesCategorizeAsContent() {
        assertEquals(AuditCategory.CONTENT, AuditCategoryClassifier.classify("article", "UPDATE"));
        assertEquals(AuditCategory.CONTENT, AuditCategoryClassifier.classify("news", "CREATE"));
        assertEquals(AuditCategory.CONTENT, AuditCategoryClassifier.classify("video", "DELETE"));
    }

    @Test
    void bulkDeactivationIsASecurityEventLikeTheSingleRowSwitch() {
        // PO-24's leaver sweep and the row's own switch are the same act. If
        // only one of them counted as SECURITY, an administrator filtering the
        // audit log for access changes would see half of them.
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("user", "UPDATE_STATUS_TO_FALSE"));
        assertEquals(AuditCategory.SECURITY, AuditCategoryClassifier.classify("user", "BULK_DEACTIVATE"));
    }

    @Test
    void unknownItemTypeDefaultsToSystem() {
        assertEquals(AuditCategory.SYSTEM, AuditCategoryClassifier.classify("something-unrecognized", "UPDATE"));
    }
}
