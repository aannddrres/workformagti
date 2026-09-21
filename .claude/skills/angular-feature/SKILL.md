---
name: angular-feature
description: Use when adding or changing anything the user sees in the Magti Portal's Angular frontend - a screen, component, dialog, button, table, form, or any visible text. Covers the ka.json/en.json obligation and check:i18n, dark-mode requirements, the NG0950 required-input trap, the article-visibility mirror, and the proxy and dev-server quirks specific to this repo.
---

# Adding or changing a screen

Angular 22, standalone components, signals. Node 22.22.3 is pinned in
`.nvmrc` and is what CI uses; the CLI refuses anything older on the 22 line
(22.22.2 included). It also accepts 24.15 or newer (`@angular/cli` engines:
`^22.22.3 || ^24.15.0 || >=26.0.0`), which is what the development machine runs.

## Every visible string is translated, in both files

The product is Georgian-only today, but **`ka.json` and `en.json` must stay in
step** and CI gates it. ngx-translate renders a missing key as the key itself,
so a gap is invisible in the browser — three admin templates once referenced a
`common` namespace that never existed (FE-08).

```bash
cd angular-frontend && npm run check:i18n
```

The checker recognises exactly two shapes: `'some.key' | translate` in a
template, and `instant('some.key')` / `stream(...)` / `get(...)` in TypeScript.
**A key composed at runtime is invisible to it** — if you build a key from a
variable, the check cannot help you and you must verify by hand.

It reports three things and fails on any: in `ka.json` but not `en.json`, in
`en.json` but not `ka.json`, and used in code but defined in neither.

## Both themes, every time

Dark mode is a **manual toggle, not `prefers-color-scheme`** —
`core/theme/theme.service.ts` sets the `dark` class on `<html>`, and every
`dark:` utility depends on it. Matching the old portal's behaviour is
deliberate. A new surface needs its `dark:` variants from the start; there is
no automatic fallback and a missing one shows as unreadable text rather than
as an error.

## The traps

**`NG0950` — a required signal input read in the constructor.** Never read
`input.required<T>()` directly in a component constructor. Wrap it in
`effect()`, or move the read to `ngOnInit`. This has bitten twice in this
project.

**`shared/article-visibility.ts` mirrors a backend rule.** It is the
reader-facing lifecycle clause of `ArticleVisibility.java`, duplicated because
the list screens filter client-side. Changing one obliges the other, and the
cases they must agree on live in
`docs/api-contract/article-visibility-cases.json` — run by the Angular suite
and by `ArticleVisibilityParityTest`. Add a case rather than edit one.

**`proxy.conf.json` forwards `/api` and `/uploads`.** A new backend-served
path needs adding there, or it 404s in development only and works in the built
image — the worst shape of bug to find late.

**`dev-serve.cmd` hardcodes the long project path on purpose.**
`.claude/launch.json` invokes it via the 8.3 short path (`MAGTIB~1`), and Vite
computes its `fs.allow` list from `process.cwd()` at startup; a short-path cwd
there mismatches the long-path form the OS normalises real requests to, and
static assets under `public/` — including `i18n/*.json` — start returning
intermittent 403s. Do not "simplify" that `cd /d`.

## Where things go

- `core/` — services, guards, interceptors, models. No components.
- `features/<screen>/` — one folder per screen; nested folders for its dialogs.
- `shared/` — components used by more than one feature, plus pure helpers.
- `shell/` — the frame everything renders inside.

Selectors use the `app` prefix; every component does, and ESLint enforces it.
The one exception is the `[portalDialog]` directive, which carries an
`eslint-disable-next-line` with its reason (`shared/portal-dialog/portal-dialog.ts`).

## The loop

```bash
cd angular-frontend && npm run lint && npm run check:i18n && npx ng test --watch=false
```

Unit tests are Vitest — no Karma, no browser, no display. `npm run lint` is
gated in CI and is clean; keep it that way. `npm run format:check` exists but
**fails today** (`prettier --list-different` flagged 184 of 220 files on
2026-09-21 — they predate any formatting pass) and is
deliberately not gated — do not run `npm run format` as part of a feature
commit.

Then look at it in the browser, in both themes, and at 1080p. The
`run-and-verify` skill has the recipes and the three ways this project's
browser checks mislead.
