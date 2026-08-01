-- Tamper-evident hash chain over audit_logs. Ports migrate.py:109-214's
-- Postgres trigger (audit_logs_canonical_string + audit_logs_chain_trigger)
-- to Oracle -- an architectural redesign, not a syntax translation. Every
-- design choice below was empirically verified against this Oracle 19c
-- instance before being written here (byte-for-byte parity with Python's
-- hashlib.sha256 on UTF-8 bytes, including Georgian text and the \x1f/\x00
-- sentinel bytes; no ORA-04091 mutating-table error; :NEW.id already holds
-- the real IDENTITY value at BEFORE INSERT time).
--
-- Differences from the Postgres design, and why:
-- 1. pg_advisory_xact_lock(72710060142) -> a single-row "tip" table
--    (audit_chain_state) locked with SELECT ... FOR UPDATE. Oracle has no
--    session-scoped advisory lock that auto-releases on commit without a
--    DBMS_LOCK.ALLOCATE_UNIQUE handle + an EXECUTE grant most schemas don't
--    have by default; a row lock needs neither and releases on commit the
--    same way. This also sidesteps the mutating-table restriction, since
--    the trigger never queries the table it's defined on.
-- 2. digest(..., 'sha256') (pgcrypto) -> STANDARD_HASH(..., 'SHA256'), a
--    built-in SQL function needing zero grants (unlike DBMS_CRYPTO.HASH,
--    which needs GRANT EXECUTE). STANDARD_HASH must be called from a SQL
--    context (SELECT ... FROM dual), not as a bare PL/SQL expression --
--    confirmed empirically (PLS-00201 otherwise).
-- 3. STANDARD_HASH does NOT accept a CLOB argument on this Oracle version
--    (ORA-00902, confirmed against both a plain column reference and a
--    trigger's :NEW reference) even though Oracle's own docs list CLOB as
--    a supported input type. details is CLOB (JSON diff blob), so it is
--    reduced via DBMS_LOB.SUBSTR to its first 32000 characters before
--    hashing -- proven byte-identical to Python on a 4767-character
--    multi-byte string. In practice this never truncates real data:
--    audit_trail.py's _clip() caps every diffed value at 200 characters,
--    and a worst-case multi-column diff lands nowhere near 32000. The same
--    32000-character reduction runs on both write (this trigger) and read
--    (AuditChainService's verify/chain-health, added separately) so a
--    hash can always be independently reproduced from the stored row.
-- 4. audit_logs_canonical_string(r audit_logs) took the whole row as a
--    single composite argument in Postgres -- SQL cannot pass a PL/SQL
--    %ROWTYPE into a query, only PL/SQL can, so a %ROWTYPE version would
--    be uncallable from the verify/chain-health SELECT queries and force
--    a second, divergence-prone reimplementation there. Using explicit
--    scalar parameters instead keeps exactly one implementation callable
--    from both the trigger (PL/SQL, passing :NEW.xxx) and the read-side
--    SQL queries (passing column references) -- preserving the single-
--    source-of-truth property the Postgres design was built around.
-- 5. to_char(timestamp, '...US') (6-digit microseconds) -> Oracle's
--    '...FF6' format token -- no direct equivalent token name, but
--    verified to produce an identical string for an identical instant.
-- 6. ip_address/user_agent are NOT derived from a GUC/session-context
--    trick here. Postgres needed one because Python has two independent
--    audit_logs write paths (ORM listeners in audit_trail.py and a
--    Core-level insert in the same module) that only a DB-side trigger
--    could reach uniformly. The Java port has one write path per caller
--    (the entity is built and saved directly), so the caller sets
--    ipAddress/userAgent on the entity itself before persisting; this
--    trigger reads whatever :NEW already holds (possibly NULL, same as
--    Python when no request context exists) and does not overwrite it.
--
-- Genuine simplification, not just risk: Postgres's lock_timeout
-- save/restore dance (migrate.py:144-165) has no Oracle equivalent need --
-- SELECT ... FOR UPDATE with no WAIT clause here relies on transactions
-- being short-lived; a NOWAIT/timeout variant can be added later if real
-- contention is observed, with no session-level GUC bookkeeping either way.

CREATE TABLE audit_chain_state (
    id        NUMBER PRIMARY KEY,
    tip_hash  VARCHAR2(64 CHAR)
);

INSERT INTO audit_chain_state (id, tip_hash) VALUES (1, NULL);

CREATE OR REPLACE FUNCTION audit_logs_canonical_string(
    p_id                    IN NUMBER,
    p_prev_hash             IN VARCHAR2,
    p_admin_id              IN NUMBER,
    p_action                IN VARCHAR2,
    p_item_type             IN VARCHAR2,
    p_item_id               IN NUMBER,
    p_timestamp             IN TIMESTAMP,
    p_category              IN VARCHAR2,
    p_details               IN CLOB,
    p_admin_name_snapshot   IN VARCHAR2,
    p_admin_email_snapshot  IN VARCHAR2,
    p_item_name_snapshot    IN VARCHAR2,
    p_ip_address            IN VARCHAR2,
    p_user_agent            IN VARCHAR2
) RETURN VARCHAR2
DETERMINISTIC
IS
    -- Unit separator + NUL sentinel, exactly as in migrate.py:119-135 --
    -- deliberately not concat_ws('|', ...), which silently skips NULL
    -- arguments and would collide ('a',NULL,'b') with ('a','b',NULL).
    us    CONSTANT VARCHAR2(1 CHAR) := CHR(31);
    nulv  CONSTANT VARCHAR2(1 CHAR) := CHR(0);
    v_details VARCHAR2(32000 CHAR);
BEGIN
    v_details := NVL(DBMS_LOB.SUBSTR(p_details, 32000, 1), nulv);
    RETURN
        TO_CHAR(p_id) || us ||
        NVL(p_prev_hash, nulv) || us ||
        TO_CHAR(p_admin_id) || us ||
        p_action || us ||
        p_item_type || us ||
        TO_CHAR(p_item_id) || us ||
        TO_CHAR(p_timestamp, 'YYYY-MM-DD"T"HH24:MI:SS.FF6') || us ||
        NVL(p_category, nulv) || us ||
        v_details || us ||
        NVL(p_admin_name_snapshot, nulv) || us ||
        NVL(p_admin_email_snapshot, nulv) || us ||
        NVL(p_item_name_snapshot, nulv) || us ||
        NVL(p_ip_address, nulv) || us ||
        NVL(p_user_agent, nulv);
END audit_logs_canonical_string;
/

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
    SELECT tip_hash INTO v_tip FROM audit_chain_state WHERE id = 1 FOR UPDATE;

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

-- Defense-in-depth invariant, not the primary race-fix (the row lock is):
-- if the trigger logic ever has a bug, the DB refuses a second genesis row
-- loudly instead of silently accepting a corrupted chain. Oracle functional
-- unique indexes omit a row entirely when the expression is NULL, so only
-- rows that are BOTH unchained-predecessor AND already-hashed collide.
CREATE UNIQUE INDEX ux_audit_logs_chain_genesis ON audit_logs (
    CASE WHEN prev_hash IS NULL AND row_hash IS NOT NULL THEN 1 END
);

-- Mirrors migrate.py's ix_audit_logs_unchained: cheap count of pre-chain
-- (NULL row_hash) rows for the dashboard's chain-health summary, without a
-- full-table scan as the table grows. Oracle has no partial-index syntax
-- (no WHERE clause on CREATE INDEX) -- the same CASE-to-NULL idiom as the
-- genesis guard above emulates it: rows with row_hash NOT NULL evaluate to
-- NULL and are omitted from the index entirely, so only the small,
-- shrinking set of pre-chain rows ever gets indexed.
CREATE INDEX ix_audit_logs_unchained ON audit_logs (
    CASE WHEN row_hash IS NULL THEN id END
);
