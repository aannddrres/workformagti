-- PO-53 (owner, 2026-10-03): the audit chain's lock waits at most 5 seconds.
--
-- V28's trigger serialises every audit insert on the one audit_chain_state
-- row (FOR UPDATE) and holds it until the inserting transaction commits. A
-- slow transaction that has written an audit row therefore queues every
-- other audited action in the portal -- sign-ins, confirmations, saves --
-- and each queued request keeps one of the 30 database connections. QA
-- round 5 measured it: a 100-article bulk archive held every sign-in for
-- about 4 s; the lock held for 30 s drained the pool and returned 503 to
-- readers too (scripts/qa/check_db_hang.py, A2 and A3).
--
-- Bulk operations now commit per article (ArticleController), so they hold
-- the lock only briefly. This is the net under whatever else could: an
-- insert that cannot have the tip within 5 s fails with ORA-30006, which
-- GlobalExceptionHandler answers as 503 "busy, try again", and frees its
-- connection instead of waiting with it.
--
-- Everything else is V28's trigger exactly: the same canonical string, the
-- same hash, the same tip update. Rows written before and after this
-- migration chain identically.
CREATE OR REPLACE TRIGGER trg_audit_logs_chain
    BEFORE INSERT ON audit_logs
    FOR EACH ROW
DECLARE
    v_tip   VARCHAR2(64 CHAR);
    v_canon VARCHAR2(32100 CHAR);
    v_hash  RAW(32);
BEGIN
    -- Row-locks the single tip row for the rest of this transaction:
    -- serializes concurrent inserts (replacing pg_advisory_xact_lock) and
    -- gives a race-free read of the current chain tip. Never touches
    -- audit_logs itself, so no ORA-04091 mutating-table error.
    SELECT tip_hash INTO v_tip FROM audit_chain_state WHERE id = 1 FOR UPDATE WAIT 5;

    :NEW.prev_hash := v_tip;  -- NULL exactly once: the true genesis row

    v_canon := audit_logs_canonical_string(
        :NEW.id, v_tip, :NEW.admin_id, :NEW.action, :NEW.item_type, :NEW.item_id,
        :NEW.timestamp, :NEW.category, :NEW.details,
        :NEW.admin_name_snapshot, :NEW.admin_email_snapshot, :NEW.item_name_snapshot,
        :NEW.ip_address, :NEW.user_agent
    );
    SELECT STANDARD_HASH(v_canon, 'SHA256') INTO v_hash FROM dual;
    :NEW.row_hash := LOWER(RAWTOHEX(v_hash));

    UPDATE audit_chain_state SET tip_hash = :NEW.row_hash WHERE id = 1;
END;
/
