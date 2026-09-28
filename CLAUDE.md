# CLAUDE.md

The working rules for this repository live in `AGENTS.md`, so that Claude
Code, Codex and anything else reading a repository read the same file rather
than two copies that drift. Everything there applies here.

@AGENTS.md

Nested notes cover each half and load when you touch a file in that directory:
`java-backend/AGENTS.md`, `angular-frontend/AGENTS.md`, `scripts/AGENTS.md`.

Each sits beside a short `CLAUDE.md` whose only instruction is `@AGENTS.md`.
That shim is load-bearing, not clutter: Claude Code discovers nested
`CLAUDE.md` and never looks for `AGENTS.md`, while Codex and Cursor do the
opposite. Delete a shim and the notes stay perfectly visible to one tool and
invisible to the other — `DocumentedFactsTest` fails the build if one goes
missing.

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
It also denies everything that wipes a Docker database volume —
`docker compose … down -v`/`--volumes`, `docker volume rm`/`prune` and
`docker system prune`. The demo and UAT stacks left the repository on
2026-09-29, but their containers and volumes can outlive it on the owner's
machine. Deny beats the broad `docker compose *` allow, in every permission
mode; those operations stay the owner's to run by hand. Machine-specific additions belong
in `.claude/settings.local.json`, which is gitignored.

**Preview.** `.claude/launch.json` has two entries: `angular-frontend` starts
the dev server on `:4200`, and `portal-local` attaches to a stack already
started by `docker-compose.local.yml` or `scripts/run-local.sh`.
