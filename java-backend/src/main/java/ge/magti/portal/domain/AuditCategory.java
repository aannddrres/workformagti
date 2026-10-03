package ge.magti.portal.domain;

/**
 * The four categories an audit action is classified into. Unlike
 * {@link Role}/{@link Permission},
 * no separate wire-value mapping is needed -- the constant names here
 * already are the wire strings ("CONTENT", "USER", "SECURITY", "SYSTEM").
 */
public enum AuditCategory {
    CONTENT,
    USER,
    SECURITY,
    SYSTEM
}
