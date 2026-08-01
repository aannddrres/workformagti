-- Mirrors models.py's WebhookConfig (models.py:666-672).
CREATE TABLE webhook_configs (
    id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    url             VARCHAR2(1000 CHAR) NOT NULL,
    is_active       NUMBER(1) DEFAULT 1,
    trigger_actions VARCHAR2(500 CHAR)
);
