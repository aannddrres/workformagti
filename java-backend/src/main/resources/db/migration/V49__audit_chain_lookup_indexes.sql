-- The health check walks backward from audit_chain_state.tip_hash by hash,
-- not by identity ID: IDs may be allocated before another transaction
-- commits its audit row. A unique row hash makes each predecessor lookup
-- bounded and rejects a duplicated/corrupt hash without altering evidence.
-- Preflight an existing deployment for duplicates before applying this
-- migration; a failure must be investigated, never silently deduplicated.
CREATE UNIQUE INDEX ux_audit_logs_row_hash ON audit_logs (row_hash);

-- Detect a detached stored tip (a newer child still points to it) without
-- scanning all audit rows on every dashboard refresh.
CREATE INDEX ix_audit_logs_prev_hash ON audit_logs (prev_hash);
