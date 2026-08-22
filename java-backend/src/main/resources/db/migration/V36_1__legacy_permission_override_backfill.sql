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
