# i18n Catalog — Georgian UI text extraction (Phase 3a)

Read-only catalog of every user-facing Georgian string found in the live
frontend/backend source, built as the seed data for Angular's
`@ngx-translate/core` infrastructure (see `docs/JAVA_ORACLE_ANGULAR_MIGRATION.md`
§3a). **No live source file was modified to produce this catalog** — every
value here was copied by reading `static/js/app-core.js`, `base-layout.html`,
`static/js/app-renderers.js`, `static/js/audit-dashboard.js`,
`static/js/dept-dashboard.js`, `static/js/app-router.js`, `article.html`,
`login.html`, and the `HTTPException detail=` / inline-SSO-page strings in
`routers/auth.py`, `routers/users.py`, `routers/articles.py`,
`routers/exports.py`, `security.py`, `routers/stats.py`. The live Python app
keeps running untouched.

## Totals

**794 keys** across **17 domain files**:

| File | Keys | File | Keys |
|---|---|---|---|
| articles.json | 162 | messaging.json | 21 |
| compliance.json | 101 | search.json | 29 |
| users.json | 72 | nav.json | 35 |
| news.json | 59 | stats.json | 23 |
| categories.json | 53 | videos.json | 23 |
| admin.json | 50 | quiz.json | 17 |
| audit.json | 50 | exports.json | 25 |
| common.json | 33 | favorites.json | 1 |
| auth.json | 40 | | |

`app-core.js` and `base-layout.html` were by far the largest sources, as
expected from the migration doc's own line-count survey — most of the volume
landed in `articles.json`, `compliance.json`, and `users.json` because those
domains have the most admin-facing CMS/CRUD screens.

## Key-naming convention

`domain.component_or_context.specific_key` in snake_case, e.g.
`articles.create_modal.title`, `common.buttons.save`,
`auth.login_page.password_label`. Domain names match the ones already used
throughout the Java backend port (`articles`, `news`, `videos`, `categories`,
`favorites`, `compliance`, `messaging`, `exports`, `audit`, `search`, `stats`,
`users`, `quiz`, `auth`), plus two new ones specific to the frontend:
`nav`/`shell` for top-level navigation/header/footer chrome not owned by one
domain, and `admin` for admin-only CMS strings (Quill editor extensions,
drag-drop upload UI) that don't cleanly belong to one content domain.

## Entry shape

```json
{
  "domain.component.key": {
    "ka": "<verbatim Georgian text copied from source>",
    "en": "<draft English translation>",
    "reviewed": false,
    "source": "static/js/app-core.js:1234",
    "context": "where/how this text is used"
  }
}
```

**Every `reviewed` field is `false`.** The `en` values are unreviewed
drafts — a human needs to check each one before it ships. `ka` values are
meant to be byte-accurate quotes from the source file at the cited line, so
nothing drifts from what real users see today.

## Known gap

The extraction agent stalled after writing all 17 domain files but before
completing its own planned duplicate-detection pass, so **semantic
duplicates across domain files have not been systematically flagged** —
only duplicates the agent happened to note inline in a `context` field
(several exist, e.g. `common.buttons.save`/`cancel`/`close`/`delete` each
document their own repeat call sites across `base-layout.html` and
`app-core.js`). A future pass, ideally done incrementally as each Angular
component is actually built in Phase 3b/3c, should treat this catalog as a
starting point rather than a final key list — some entries here will turn
out to be near-duplicates of each other once real templates are written
against them.
