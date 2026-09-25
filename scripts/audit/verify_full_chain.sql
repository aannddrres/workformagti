-- Verifies the WHOLE audit hash chain (V28), read-only. For a DBA checking a
-- restored backup, or anyone who needs more than the portal's own check,
-- which covers only the newest 500 rows (/api/audit-logs/chain-health).
--
-- Run as the portal's schema owner, in SQL*Plus or SQLcl:
--
--     @scripts/audit/verify_full_chain.sql
--
-- It only reads. Nothing here writes, locks or needs a grant beyond what the
-- schema owner has. Each statement scans audit_logs once and recomputes every
-- row's hash, so allow about a minute per million rows.
--
-- The chain is followed by hash, never by id. V28's trigger links a row to
-- the tip it finds once it holds the tip's lock, and ids are taken before
-- that, so under simultaneous writes a row can follow a higher id. That is
-- not damage (AuditChainService.linked, ConcurrentAuditChainIntegrationTest).
--
-- An intact chain is a single line from one genesis row to the recorded tip:
--
--   hash_mismatches         0  every row still hashes to what it stored
--   dangling_links          0  every row's predecessor is still there
--   forks                   0  no predecessor has two successors
--   genesis_rows            1  exactly one row names no predecessor
--   rows_without_successor  1  the line has exactly one end...
--   tip_is_the_end          1  ...and audit_chain_state names that end, so
--                              deleting the newest rows shows too
--
-- verdict is INTACT, BROKEN or EMPTY. Rows written before V28 carry no hash
-- and are counted in unchained_rows, not treated as damage.
--
-- What no in-database check can catch: someone able to rewrite every row
-- from the damage to the tip, and audit_chain_state with them. That needs a
-- copy of the tip kept outside the database.

WITH chained AS (
    SELECT /*+ MATERIALIZE */ id, prev_hash, row_hash,
           LOWER(RAWTOHEX(STANDARD_HASH(audit_logs_canonical_string(
               id, prev_hash, admin_id, action, item_type, item_id, timestamp, category, details,
               admin_name_snapshot, admin_email_snapshot, item_name_snapshot, ip_address, user_agent
           ), 'SHA256'))) AS recomputed
    FROM audit_logs
    WHERE row_hash IS NOT NULL
),
named AS (
    SELECT prev_hash, COUNT(*) AS successors
    FROM chained
    WHERE prev_hash IS NOT NULL
    GROUP BY prev_hash
),
checks AS (
    SELECT
        (SELECT COUNT(*) FROM chained) AS chained_rows,
        (SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NULL) AS unchained_rows,
        (SELECT COUNT(*) FROM chained WHERE recomputed <> row_hash) AS hash_mismatches,
        (SELECT COUNT(*) FROM chained c
          WHERE c.prev_hash IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM chained p WHERE p.row_hash = c.prev_hash)) AS dangling_links,
        (SELECT COUNT(*) FROM named WHERE successors > 1) AS forks,
        (SELECT COUNT(*) FROM chained WHERE prev_hash IS NULL) AS genesis_rows,
        (SELECT COUNT(*) FROM chained c
          WHERE NOT EXISTS (SELECT 1 FROM named n WHERE n.prev_hash = c.row_hash)) AS rows_without_successor,
        (SELECT COUNT(*) FROM audit_chain_state s JOIN chained c ON c.row_hash = s.tip_hash
          WHERE s.id = 1
            AND NOT EXISTS (SELECT 1 FROM named n WHERE n.prev_hash = c.row_hash)) AS tip_is_the_end
    FROM dual
)
SELECT checks.*,
       CASE
           WHEN chained_rows = 0 THEN 'EMPTY'
           WHEN hash_mismatches = 0 AND dangling_links = 0 AND forks = 0 AND genesis_rows = 1
                AND rows_without_successor = 1 AND tip_is_the_end = 1 THEN 'INTACT'
           ELSE 'BROKEN'
       END AS verdict
FROM checks;

-- Where to look when the verdict is BROKEN: the first 50 rows at fault, with
-- the reason. A row can appear once per reason.

WITH chained AS (
    SELECT /*+ MATERIALIZE */ id, prev_hash, row_hash,
           LOWER(RAWTOHEX(STANDARD_HASH(audit_logs_canonical_string(
               id, prev_hash, admin_id, action, item_type, item_id, timestamp, category, details,
               admin_name_snapshot, admin_email_snapshot, item_name_snapshot, ip_address, user_agent
           ), 'SHA256'))) AS recomputed
    FROM audit_logs
    WHERE row_hash IS NOT NULL
),
named AS (
    SELECT prev_hash, COUNT(*) AS successors
    FROM chained
    WHERE prev_hash IS NOT NULL
    GROUP BY prev_hash
)
SELECT id, reason FROM (
    SELECT id, 'no longer hashes to its stored row_hash' AS reason
    FROM chained WHERE recomputed <> row_hash
    UNION ALL
    SELECT c.id, 'names a predecessor that is not there'
    FROM chained c
    WHERE c.prev_hash IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM chained p WHERE p.row_hash = c.prev_hash)
    UNION ALL
    SELECT c.id, 'shares its predecessor with another row'
    FROM chained c JOIN named n ON n.prev_hash = c.prev_hash
    WHERE n.successors > 1
    UNION ALL
    SELECT id, 'names no predecessor, and is not the only such row'
    FROM chained
    WHERE prev_hash IS NULL
      AND (SELECT COUNT(*) FROM chained WHERE prev_hash IS NULL) > 1
)
ORDER BY id
FETCH FIRST 50 ROWS ONLY;
