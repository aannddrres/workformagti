-- Product decision: article/news feedback is not part of the portal. Keep only
-- non-content migration evidence (when the removal ran and how many rows it
-- removed); no feedback payload is copied to an application-owned backup.
CREATE TABLE data_migration_audit (
    migration_id VARCHAR2(100) PRIMARY KEY,
    executed_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    affected_rows NUMBER(19, 0) NOT NULL,
    CONSTRAINT ck_data_migration_audit_rows CHECK (affected_rows >= 0)
);

INSERT INTO data_migration_audit (migration_id, executed_at, affected_rows)
SELECT 'V43_REMOVE_KNOWLEDGE_FEEDBACK', SYSTIMESTAMP, COUNT(*)
FROM knowledge_feedback;

-- Explicit WHERE keeps the destructive intent visible and lets Oracle commit
-- an empty object before it is dropped. The recycle-bin object therefore
-- cannot retain the deleted feedback payload.
DELETE FROM knowledge_feedback WHERE 1 = 1;

DROP TABLE knowledge_feedback;

