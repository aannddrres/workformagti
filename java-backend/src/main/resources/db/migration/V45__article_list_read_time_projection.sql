-- Keep article list responses CLOB-free while preserving the legacy read_time
-- contract. Oracle owns the derived scalar so every writer (including future
-- SSO-integrated services) gets the same value; no application-only cache or
-- mutable client field can drift from content.
-- Oracle DDL auto-commits. The guard also makes recovery safe if a later
-- statement fails after the column was added but before Flyway recorded V45.
DECLARE
    v_column_count NUMBER;
BEGIN
    SELECT COUNT(*)
    INTO v_column_count
    FROM user_tab_columns
    WHERE table_name = 'ARTICLES'
      AND column_name = 'READ_TIME';

    IF v_column_count = 0 THEN
        EXECUTE IMMEDIATE '
            ALTER TABLE articles ADD (
                read_time NUMBER(10) DEFAULT 1 NOT NULL,
                CONSTRAINT ck_articles_read_time CHECK (read_time >= 1)
            )';
    END IF;
END;
/

UPDATE articles
SET read_time = GREATEST(
    1,
    FLOOR(NVL(REGEXP_COUNT(content, '[^[:space:]]+'), 0) / 150)
);

CREATE OR REPLACE TRIGGER trg_articles_read_time
    -- Oracle does not permit a LOB in an UPDATE OF trigger column list
    -- (ORA-25006). Recomputing on every row update is deterministic and keeps
    -- non-Java writers fail-safe as well.
    BEFORE INSERT OR UPDATE ON articles
    FOR EACH ROW
BEGIN
    :NEW.read_time := GREATEST(
        1,
        FLOOR(NVL(REGEXP_COUNT(:NEW.content, '[^[:space:]]+'), 0) / 150)
    );
END;
/
