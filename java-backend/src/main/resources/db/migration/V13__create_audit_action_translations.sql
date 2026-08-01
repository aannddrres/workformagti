-- Mirrors models.py's AuditActionTranslation (models.py:658-664).
CREATE TABLE audit_action_translations (
    id       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action   VARCHAR2(50 CHAR) NOT NULL,
    label_ka VARCHAR2(200 CHAR) NOT NULL,
    CONSTRAINT uq_audit_action_translations_action UNIQUE (action)
);
