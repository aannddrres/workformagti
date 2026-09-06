# angular-frontend — notes for coding agents

Angular 22, standalone components, signals. Node 22.22.3 is pinned in
`.nvmrc` and the CLI refuses a release one patch old, so use it. The product
and the cross-cutting rules are in the repository root `AGENTS.md`.

## Commands

```bash
npm ci && npm run lint && npm run check:i18n && npx ng test --watch=false
```

Unit tests are Vitest through `@angular/build:unit-test` — no Karma, no
browser, no display. `npx ng build --configuration production` is what CI
builds, and CI additionally greps the built `index.html` for inline event
handlers and inline script bodies: `nginx.conf.template` serves
`script-src 'self'`, so re-enabling `optimization.styles.inlineCritical`
would break it silently.

Playwright specs in `e2e/` need a stack already running — the config starts
nothing. `global-setup.ts` logs the personas in once so the
ten-logins-per-minute limiter does not trip the suite.

## Traps this project has actually hit

**`NG0950` — a required signal input read in a constructor.** Never read
`input.required<T>()` directly in a component constructor; wrap it in
`effect()` or move it to `ngOnInit`. This has bitten twice.

**Dark mode is a manual toggle, not `prefers-color-scheme`.**
`core/theme/theme.service.ts` sets the `dark` class on `<html>`, and every
`dark:` utility depends on it. Matching the old portal is deliberate. A new
surface needs its `dark:` variants from the start — a missing one shows as
unreadable text, not as an error.

**The i18n check sees only two shapes.** `scripts/check-i18n.mjs` matches
`'some.key' | translate` and `instant('some.key')` / `stream(...)` / `get(...)`.
**A key composed at runtime is invisible to it.** Every key must exist in both
`public/i18n/ka.json` and `public/i18n/en.json`; ngx-translate renders a
missing key as the key itself, so a gap does not look like a bug in the
browser. Three admin templates once referenced a `common` namespace that never
existed (FE-08).

**`shared/article-visibility.ts` is half of a rule written twice.** The other
half is `ArticleVisibility.java`, duplicated because list screens filter
client-side. Change one and you must change the other; the cases both are
tested against are in `docs/api-contract/article-visibility-cases.json`.

**`dev-serve.cmd` hardcodes the long project path on purpose.**
`.claude/launch.json` invokes it via the 8.3 short path (`MAGTIB~1`), and Vite
computes `fs.allow` from `process.cwd()` at startup; a short-path cwd there
mismatches the long-path form the OS normalises requests to, producing
intermittent 403s on `public/i18n/*.json` and other static assets.

**`proxy.conf.json` forwards `/api` and `/uploads`.** A new backend-served
path needs adding there, or it 404s in development only and works in the built
image — the worst shape of bug to find late.

## Checking it in a browser

For anything visible, look at the running app rather than reading the source.
Two recurring false alarms: a fix that "did not take" is usually the dev
server not having pushed a fresh bundle to the tab (confirm with
`fetch('/main.js', {cache: 'no-store'})` before doubting the code), and
console entries persist across navigations in the same tab, so an errors-only
view does not mean "an error just now".

## Never

- **Never run `npm run format` inside a feature commit.** Prettier has never
  been run over this codebase — 188 of 211 files — so it is a 188-file diff
  that belongs in a commit of its own, on a clean tree. `format:check` exists
  and fails today; it is deliberately not gated in CI.
- **Never leave `npm run lint` red.** It is gated, and its rule set is the
  recommended baseline and nothing more, chosen so it could gate from its
  first commit. Tighten it one rule at a time, fixes in the same commit.
- **Never add a translation key to only one of the two files.**
- **Never "simplify" the `cd /d` in `dev-serve.cmd`.**
