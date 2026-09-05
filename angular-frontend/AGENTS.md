# angular-frontend — notes for coding agents

Angular 22, standalone components, signals. Node 22.22.3 is pinned in
`.nvmrc` and the Angular CLI refuses a release one patch old, so use it. See
the repository root `AGENTS.md` for the product and the cross-cutting rules.

## Commands

```bash
npm ci && npx ng test --watch=false && npm run check:i18n
```

Unit tests are Vitest through `@angular/build:unit-test` — no Karma, no
browser, no display needed.

`npm run lint` is gated in CI and is clean; keep it that way. Its rule set is
the recommended baseline and nothing more, chosen so it could gate from the
first commit instead of being switched off on the second — tighten it by
turning rules on one at a time, with the fixes in the same commit.

`npm run format:check` exists and **fails today**: 188 of 211 files predate
any formatting pass, so Prettier has never been run over this codebase. It is
deliberately not in CI. Running `npm run format` is a 188-file diff and
belongs in a commit of its own, on a clean tree — not folded into a feature.

`npx ng build --configuration production` is what CI builds, and CI
additionally greps the built `index.html` for inline event handlers and inline
script bodies, because `nginx.conf.template` serves `script-src 'self'` and
re-enabling `optimization.styles.inlineCritical` would silently break it.

Playwright specs live in `e2e/` and need a stack already running — the config
does not start one. `global-setup.ts` logs the personas in once so the
ten-logins-per-minute limiter does not trip the suite.

## Layout

- `core/` — no components. `auth/` (service, guards, `idle-session.service`),
  `http/` interceptors, `models/`, ~22 HTTP services, `theme/theme.service`,
  `accessibility/font-scale.service`.
- `features/` — one folder per screen. `admin-content/` and `dashboard/` are
  the big ones.
- `shared/` — reusable components plus pure helpers: `article-visibility.ts`,
  `category-tree.ts`, `department-matcher.ts`, `ka-date.ts`,
  `permission-catalog.ts`, `youtube.ts`.
- `shell/` — `app-shell`, the frame everything renders inside.

## Traps this project has actually hit

**`NG0950` — a required signal input read in the constructor.** Never read
`input.required<T>()` directly in a component constructor; wrap it in
`effect()` or move it to `ngOnInit`. This has bitten twice.

**Dark mode is a manual toggle, not `prefers-color-scheme`.**
`core/theme/theme.service.ts` sets the `dark` class on `<html>`; every `dark:`
class in the templates depends on it. Matching the old portal's behaviour is
deliberate. Both themes are required for any new screen.

**i18n keys are checked statically, and only two shapes are recognised.**
`scripts/check-i18n.mjs` matches `'some.key' | translate` and
`instant('some.key')` / `stream(...)` / `get(...)`. Keys composed at runtime
are out of scope and will not be caught. Every key must exist in **both**
`public/i18n/ka.json` and `public/i18n/en.json`; ngx-translate renders a
missing key as the key itself, so a gap is invisible in the browser. Three
admin templates once referenced a `common` namespace that never existed
(FE-08).

**`article-visibility.ts` mirrors a backend rule.** It is the reader-facing
subset of `ArticleVisibility.java`. Change one and you must change the other;
`docs/api-contract/article-visibility-cases.json` holds the cases both are
tested against.

**The dev server is launched through `dev-serve.cmd`, which hardcodes the long
path on purpose.** `.claude/launch.json` invokes it via the 8.3 short path
(`MAGTIB~1`), and Vite computes its `fs.allow` list from `process.cwd()` at
startup — a short-path cwd there mismatches the long-path form the OS
normalises real requests to, producing intermittent 403s on `public/i18n/*.json`
and other static assets. Do not "simplify" that `cd /d`.

`proxy.conf.json` forwards `/api` **and** `/uploads` to the backend; a new
backend-served path needs adding there or it 404s in development only.

## Verifying in a browser

When a change is visible, check it in the running app rather than by reading
the source. Two recurring false alarms: a fix that "did not take" is often the
dev server not having pushed a fresh bundle to the tab (confirm with
`fetch('/main.js', {cache: 'no-store'})` before doubting the code), and
console readings retain entries across navigations in the same tab, so filter
by recency rather than trusting an errors-only view to mean "an error just
now".
