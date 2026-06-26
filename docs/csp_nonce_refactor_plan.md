# CSP Hardening Plan — Dropping `'unsafe-inline'` Safely

> **Why this is a separate effort:** `base-layout.html` currently relies on **114
> inline `onclick=` handlers**, at least one inline `<script>` block, and inline
> `style=` attributes. Removing `'unsafe-inline'` from the CSP today would silently
> break every button and inline style (and `pytest` would still pass — it only checks
> backend routes). So `'unsafe-inline'` stays until the inline code is migrated. This
> plan sequences that migration.

## Current state
- CSP (`main.py:~561`): `script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com; style-src 'self' 'unsafe-inline' …` (the `cdn.tailwindcss.com` origin was already removed when the CDN was dropped).
- Inline surface: `grep -c 'onclick=' base-layout.html` → ~114; inline `<script>` blocks; inline `style=` attrs; server-rendered pages in `main.py` (SSO/etc.) also use inline handlers.

## Target
`script-src 'self' 'nonce-<per-request>'` and `style-src 'self'` — no `'unsafe-inline'`.

## Phases (each independently shippable; verify in the preview after each)

### Phase 1 — Remove inline event-handler attributes (`onclick=` → delegation)
- Replace `onclick="fn(args)"` with `data-action="fn"` + `data-args` attributes.
- Add **one** delegated listener per page bundle:
  ```js
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (!el) return;
    const fn = window[el.dataset.action];
    if (typeof fn === 'function') fn(el, e);
  });
  ```
- Mechanical, high-volume change (114 sites in `base-layout.html` + the server-rendered
  pages in `main.py`). Do it page-by-page; the existing `window.*` function exports
  (e.g. `window.downloadExportXlsx`) already make handlers reachable.
- Also migrate other inline `on*` attributes (`onchange`, `onsubmit`, `oninput`).

### Phase 2 — Externalize inline `<script>` blocks
- Move the remaining inline `<script>` blocks (e.g. the sidebar toggle helper) into the
  `static/js` bundle. After this, no `<script>` without `src` remains.

### Phase 3 — Inline styles
- Replace inline `style=` attributes with utility classes (or `class` toggles). The few
  genuinely dynamic ones (computed widths, chart canvas sizing) can use CSS custom
  properties set via JS, or keep `style-src 'unsafe-inline'` *only* if a handful remain
  and accept that as a lesser risk than script-src.

### Phase 4 — Per-request nonce + CSP flip
- Add a middleware that generates a `nonce` per request, exposes it to the template
  (e.g. via `request.state.csp_nonce`), and stamps `<script nonce="{{nonce}}">` on any
  intentionally-inline script that must stay.
- Update the CSP middleware (`main.py:~561`) to emit `script-src 'self' 'nonce-{nonce}'`
  (drop `'unsafe-inline'`). Keep the CDN origins for jsdelivr/cdnjs (Chart.js, Quill,
  DOMPurify, Font Awesome) unless those are also self-hosted.
- **Gotcha:** the CSP is currently a static string built once per response in the
  middleware; nonces require generating the value *before* the body renders and injecting
  the same value into both the header and the template — so this couples the CSP
  middleware with the template/response rendering.

## Verification (per phase)
- Preview smoke-test: every button/menu still works; no CSP violations in the console
  (`Refused to execute inline script…`); brand colors/layout/charts/editor intact.
- `pytest tests/test_resilience.py -q` for backend route integrity.
- Temporarily set `Content-Security-Policy-Report-Only` with the hardened policy in
  staging to catch violations before enforcing.

## Effort / risk
- **Phase 1** is the bulk (≈114 + server-rendered handlers) — Medium effort, Low risk if
  done page-by-page with preview verification.
- **Phase 4** is Low effort but couples CSP+rendering — Medium risk; ship Report-Only first.
