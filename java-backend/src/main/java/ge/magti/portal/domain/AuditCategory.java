package ge.magti.portal.domain;

/**
 * Mirrors the four category strings models.py's classify_audit_category
 * assigns (models.py:409-415). Unlike {@link Role}/{@link Permission},
 * no separate wire-value mapping is needed -- the constant names here
 * already are the wire strings ("CONTENT", "USER", "SECURITY", "SYSTEM").
 */
public enum AuditCategory {
    CONTENT,
    USER,
    SECURITY,
    SYSTEM
}
