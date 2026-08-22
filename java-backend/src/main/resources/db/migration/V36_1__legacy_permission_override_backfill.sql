-- Preserve the authorization decisions made before Phase 6.
--
-- users.permissions was the complete effective permission set. Phase 6 stores
-- only differences from the current role defaults, so every legacy value can
-- be translated deterministically:
--   legacy has it + role does not  -> ALLOW
--   legacy lacks it + role has it  -> DENY
--   both agree                     -> INHERIT (no row)
--
-- content.manage is intentionally absent from this legacy catalog because it
-- did not exist before Phase 6. Removed no-op values (articles.view and
-- users.manage) are ignored rather than carried into the new whitelist.
--
-- system.audit is the one permission where absence from users.permissions is
-- NOT evidence of a denial, so only its ALLOW direction is migrated. The
-- Python original spelled it "system:audit" with a colon, deliberately, so
-- that it landed in the separate colon-named role_permissions catalog rather
-- than this column (security.py:376-381); DEFAULT_PERMISSIONS_BY_ROLE
-- (security.py:385-405) therefore never wrote the dotted string here for any
-- role. Java's port retired that catalog and folded the permission into the
-- role defaults instead -- correctly, since a manager really did hold
-- system:audit there.
--
-- Treating that absence as a decision would write DENY for every manager and
-- content admin carrying Python-era defaults, and an explicit DENY outranks
-- the role default permanently: /api/audit-logs, its export, verify and
-- chain-health, plus can_view_audit_log, would close for exactly the people
-- the role grants them to. A local Oracle cannot show this -- rows seeded by
-- the Java app carry the dotted string and agree -- so production would be
-- the first place it appeared, unevenly, across Python-era rows only.
--
-- Presence still counts: an operator explicitly granted system.audit through
-- the pre-Phase-6 flat endpoint keeps it as an ALLOW.
--
-- system.audit is the ONLY divergence between the two role tables. Every
-- other catalog value below grants identically in Python and Java, so both
-- directions are safe for them.

MERGE INTO user_permission_overrides target
USING (
    WITH legacy_catalog (permission) AS (
        SELECT 'articles.edit'    FROM dual UNION ALL
        SELECT 'articles.publish' FROM dual UNION ALL
        SELECT 'articles.archive' FROM dual UNION ALL
        SELECT 'videos.archive'   FROM dual UNION ALL
        SELECT 'compliance.assign' FROM dual UNION ALL
        SELECT 'reports.export'   FROM dual UNION ALL
        SELECT 'system.audit'     FROM dual
    ),
    decisions AS (
        SELECT
            u.id AS user_id,
            catalog.permission,
            CASE
                WHEN u.role = 'manager'
                     AND catalog.permission IN ('reports.export', 'system.audit') THEN 1
                WHEN u.role = 'content_admin'
                     AND catalog.permission IN (
                         'articles.edit', 'articles.publish', 'articles.archive',
                         'videos.archive', 'compliance.assign', 'system.audit'
                     ) THEN 1
                WHEN u.role = 'admin' THEN 1
                ELSE 0
            END AS role_grants,
            CASE WHEN EXISTS (
                SELECT 1
                FROM JSON_TABLE(
                    NVL(u.permissions, TO_CLOB('[]')),
                    '$[*]' COLUMNS (
                        permission VARCHAR2(50 CHAR) PATH '$'
                    )
                ) stored
                WHERE stored.permission = catalog.permission
            ) THEN 1 ELSE 0 END AS legacy_grants
        FROM users u
        CROSS JOIN legacy_catalog catalog
    )
    SELECT
        user_id,
        permission,
        CASE WHEN legacy_grants = 1 THEN 'ALLOW' ELSE 'DENY' END AS state
    FROM decisions
    WHERE legacy_grants <> role_grants
      AND NOT (permission = 'system.audit' AND legacy_grants = 0)
) source
ON (target.user_id = source.user_id AND target.permission = source.permission)
WHEN NOT MATCHED THEN INSERT (
    user_id,
    permission,
    state,
    updated_at,
    updated_by
) VALUES (
    source.user_id,
    source.permission,
    source.state,
    CAST(SYSTIMESTAMP AT TIME ZONE 'Asia/Tbilisi' AS TIMESTAMP),
    NULL
);
