package ge.magti.portal.audit;

import ge.magti.portal.domain.AuditCategory;

import java.util.Map;
import java.util.Set;

/**
 * Mirrors models.py's classify_audit_category (models.py:409-415) and its
 * three backing sets/map (models.py:381-406) exactly. Pure and DB-free --
 * safe to port now even though the rest of the Audit domain mostly isn't
 * (see the migration doc's §1a).
 */
public final class AuditCategoryClassifier {

    private static final Set<String> SECURITY_ACTIONS = Set.of(
            "LOGIN", "LOGIN_SSO", "LOGIN_FAILED", "PASSWORD_CHANGE", "PASSWORD_RESET",
            "PASSWORD_RESET_REQUEST", "CREATE_USER", "UPDATE_PERMISSIONS",
            "VIEW_AUDIT_LOG", "EXPORT_AUDIT_LOG");

    private static final Set<String> USER_ACTIONS = Set.of(
            "VIEW", "MARK_READ", "SEND_MESSAGE", "SEND_MANUAL_REMINDER", "READ_REMINDER");

    private static final Map<String, AuditCategory> CATEGORY_BY_ITEM_TYPE = Map.ofEntries(
            Map.entry("news", AuditCategory.CONTENT),
            Map.entry("category", AuditCategory.CONTENT),
            Map.entry("article", AuditCategory.CONTENT),
            Map.entry("video", AuditCategory.CONTENT),
            Map.entry("feedback", AuditCategory.CONTENT),
            Map.entry("required_reading", AuditCategory.CONTENT),
            Map.entry("user", AuditCategory.USER),
            Map.entry("readings", AuditCategory.SYSTEM),
            Map.entry("file", AuditCategory.SYSTEM),
            Map.entry("system", AuditCategory.SYSTEM),
            Map.entry("team_stats", AuditCategory.SYSTEM));

    private AuditCategoryClassifier() {
    }

    public static AuditCategory classify(String itemType, String action) {
        if (SECURITY_ACTIONS.contains(action) || (action != null && action.startsWith("UPDATE_STATUS_TO_"))) {
            return AuditCategory.SECURITY;
        }
        if (USER_ACTIONS.contains(action)) {
            return AuditCategory.USER;
        }
        return CATEGORY_BY_ITEM_TYPE.getOrDefault(itemType, AuditCategory.SYSTEM);
    }
}
