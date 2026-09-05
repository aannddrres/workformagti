# CLAUDE.md

The working rules for this repository live in `AGENTS.md`, so that Claude
Code, Codex and anything else reading a repository read the same file rather
than two copies that drift. Everything there applies here.

@AGENTS.md

Nested files cover each half and are read automatically when you work inside
them: `java-backend/AGENTS.md`, `angular-frontend/AGENTS.md`,
`scripts/AGENTS.md`.

## Claude-specific

**Skills.** Four project skills open on their own when the matching work
starts. Invoke one directly if it does not:

| Skill | Reach for it when |
|---|---|
| `access-change` | Changing who may call an endpoint or see a record |
| `run-and-verify` | Starting the stack, or proving a change works |
| `angular-feature` | Adding or changing a screen, component or translated string |
| `db-migration` | Adding a Flyway migration |

Five vendored skills are also installed (`angular-developer`, `db`,
`java-architect`, `test-master`, `debugging-wizard`). They are third-party and
know nothing about this project; the four above do. On a fresh clone run
`scripts/link-skills.ps1` once, or the vendored five will not be visible.

**Permissions.** `.claude/settings.json` is committed and holds the
project-scoped allowlist, so a new session does not re-approve the same
commands. It denies reading `.env`, `.presentation.env` and `.uat.env` — those
hold real local credentials and nothing in the codebase needs them opened.
Machine-specific additions belong in `.claude/settings.local.json`, which is
gitignored.

**Preview.** `.claude/launch.json` has two entries: `angular-frontend` starts
the dev server on `:4200`, and `portal-local` attaches to a stack already
started by `docker-compose.local.yml` or `scripts/run-local.sh`.
