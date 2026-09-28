package ge.magti.portal.export;

/** The six legally stored data families approved by PO-14. */
public enum AdminExportFamily {
    AUDIT_LEDGER("ADMIN_AUDIT_LEDGER", "აუდიტის სრული ჟურნალი", "EXPORT_ADMIN_AUDIT_LEDGER"),
    READ_EVIDENCE("ADMIN_READ_EVIDENCE", "ოფიციალური გაცნობის მტკიცებულება", "EXPORT_ADMIN_READ_EVIDENCE"),
    ARTICLE_VIEWS("ADMIN_ARTICLE_VIEWS", "სტატიების გახსნის ისტორია", "EXPORT_ADMIN_ARTICLE_VIEWS"),
    SEARCH_HISTORY("ADMIN_SEARCH_HISTORY", "ძებნის ისტორია", "EXPORT_ADMIN_SEARCH_HISTORY"),
    QUIZ_ATTEMPTS("ADMIN_QUIZ_ATTEMPTS", "ქვიზის მცდელობები", "EXPORT_ADMIN_QUIZ_ATTEMPTS"),
    CHANGE_EVENTS("ADMIN_CHANGE_EVENTS", "ცვლილებები და უსაფრთხოების მოვლენები", "EXPORT_ADMIN_CHANGE_EVENTS");

    private final String code;
    private final String title;
    private final String auditAction;

    AdminExportFamily(String code, String title, String auditAction) {
        this.code = code;
        this.title = title;
        this.auditAction = auditAction;
    }

    public String code() { return code; }
    public String title() { return title; }
    public String auditAction() { return auditAction; }
    public String filenamePrefix() { return code.toLowerCase(java.util.Locale.ROOT); }
}
