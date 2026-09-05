> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Article Versioning, Content Diffing & Operator Notification — Engineering Specification

> Status: approved blueprint. Implementation target is the **live** entrypoint
> (`main.py`), with parity edits in the strangler copy (`app/routers/articles.py`).
> No new third-party dependencies — `beautifulsoup4` (`requirements.txt:20`) and
> stdlib `difflib` cover everything.

## Context

The portal already persists article revisions: `update_article` archives the
pre-edit state into `ArticleHistory`, and an admin modal lists revisions with a
one-click restore. Missing pieces this spec adds:

1. A stable **version number** (`version_id`) on each snapshot.
2. A **rich-text-safe HTML diff** so admins see *what changed* between revisions
   without rendering broken HTML.
3. An **opt-in real-time push** so online operators learn that a KB article they
   rely on was just edited.

### Critical pre-finding — single live entrypoint

Both dev (`start_server.bat` → `uvicorn main:app`) and prod (`Dockerfile:42` →
`gunicorn main:app`) serve **`main.py`**. The `app/main.py` factory and
`app/routers/articles.py` are parallel strangler-refactor code **not currently
wired into a served app**. Therefore:

- **Canonical hook points (live):** `main.py` — `update_article` (`main.py:2145`),
  `create_article` (`main.py:2092`), `get_article_history` (`main.py:2327`),
  `restore_article_version` (`main.py:2354`).
- The mirror copy in `app/routers/articles.py:236` receives the **same** edits
  for parity (to avoid drift at cutover) but is **not** on the live path.

---

## 1. Schema — `ArticleHistory` (models.py) + migration (migrate.py)

### Current state (`models.py:385`)
```python
class ArticleHistory(Base):
    __tablename__ = "article_history"
    id          = Column(Integer, primary_key=True, index=True)
    article_id  = Column(Integer, ForeignKey("articles.id"), nullable=False, index=True)
    title       = Column(String, nullable=False)
    content     = Column(Text, nullable=False)          # full Quill HTML snapshot
    updated_at  = Column(DateTime, default=datetime.utcnow)
    updated_by  = Column(Integer, ForeignKey("users.id"), nullable=False)
```

### Decision: snapshot, not delta
The table already stores full `content`, and restore (`main.py:2401`) copies the
snapshot back verbatim. Switching to deltas would break restore and add
reconstruction cost for a small, admin-write-only table. **Keep full snapshots.**
Diffs are computed on demand (Section 2), never stored — avoiding stale/duplicated
diff text and keeping the write path cheap.

### Field addition (`models.py`, edit the class)
```python
    # The article.version value that THIS snapshot represents (i.e. the version
    # number the article carried *before* the edit that created this row). Lets
    # the UI label revisions stably ("ვერსია 4") instead of by list index.
    version_id  = Column(Integer, nullable=True, index=True)
```
- `nullable=True` — existing rows predate the column; UI falls back to descending
  list index when `version_id is None`.
- `updated_at` / `updated_by` already satisfy the timestamp and updating-user
  requirements; `article_id` is already indexed for the history query.

### Migration (`migrate.py` → `_ADDED_COLUMNS`, `migrate.py:57`)
Append one idempotent tuple (reuses the existing `ensure_columns()`
ADD-COLUMN-if-missing machinery — safe across SQLite dev + Postgres prod and
across racing workers):
```python
    ("article_history", "version_id", "INTEGER", "NULL"),
```
No extra index DDL is needed: `create_all` covers fresh DBs (the model's
`index=True`), and existing DBs simply gain the nullable column. An optional
one-time backfill can be a separate `scripts/` job — not required for correctness.

### Capture at write time
In `update_article` (`main.py:2175`) and `restore_article_version`
(`main.py:2392`), stamp the **current** version onto the snapshot before the
article's version is bumped:
```python
    article_history = models.ArticleHistory(
        article_id=db_article.id,
        title=db_article.title,
        content=db_article.content,
        updated_by=current_admin.id,
        version_id=db_article.version,        # NEW — snapshot's own version
    )
```
Apply the identical addition in `app/routers/articles.py:266` (parity).

---

## 2. Rich-Text HTML Diff Engine

### Problem
Content is raw Quill HTML. A naive `difflib` over the HTML *string* splits tags
(`<di`…`v>`), producing unclosed elements and stringified markup that corrupts
the parent DOM. The diff must be **structure-aware** and **XSS-safe**.

### Strategy
1. Parse both revisions with BeautifulSoup (`html.parser`, already used in
   `scripts/migrate_legacy_html.py:263`).
2. Reduce each document to an **ordered list of block-level text segments**
   (`p, li, h1–h6, blockquote, td, th, pre`, plus bare text). We diff *text*,
   never tags — the engine can never emit a broken tag. **Link/media awareness:**
   each block's token string folds in structural tokens for any contained
   `<a href>` / `<img src/alt>` (`[Link: …]`, `[Image: …]`), so a changed link
   target or swapped image is flagged even when visible text is unchanged.
3. Block-level pass with `difflib.SequenceMatcher` → equal / insert / delete /
   replace.
4. For `replace` blocks, run a **word-level** `SequenceMatcher` and wrap runs in
   semantic spans.
5. **Reassemble brand-new, safe markup** from `html.escape`-d text plus our own
   `<ins>/<del>` wrappers. Output is constructed from escaped text (never spliced
   into the originals) → structurally valid and injection-proof by construction;
   no `bleach` needed.

### Output contract
A standalone HTML fragment for the Quick Look modal:
```html
<div class="diff-line">Unchanged paragraph text.</div>
<div class="diff-line"><del class="bg-red-100 line-through dark:bg-red-900/40">old wording</del>
<ins class="bg-green-100 dark:bg-green-900/40">new wording</ins></div>
<div class="diff-line"><ins class="bg-green-100 dark:bg-green-900/40">A fully added paragraph.</ins></div>
```
Tailwind classes are theme-aware (light + dark) per project rules.

### Module — `diffing.py` (project root, imported by `main.py`)
```python
"""Structure-aware, XSS-safe HTML diff for Quill article revisions.

Diffs the TEXT content of block elements only; output markup is rebuilt from
escaped text, so tags can never be split and injected HTML can never execute.
Zero new deps: BeautifulSoup (requirements.txt) + difflib (stdlib).
"""
import html
from difflib import SequenceMatcher
from bs4 import BeautifulSoup

_BLOCK_TAGS = ("p", "li", "h1", "h2", "h3", "h4", "h5", "h6",
               "blockquote", "pre", "td", "th")

INS = '<ins class="bg-green-100 dark:bg-green-900/40 no-underline rounded px-0.5">{}</ins>'
DEL = '<del class="bg-red-100 dark:bg-red-900/40 line-through rounded px-0.5">{}</del>'


def _block_text(node) -> str:
    """Text of a block PLUS structural tokens for links/media.

    Asset-change blindness fix: if a block contains <a>/<img>, fold their
    href/src (and alt) into the token stream so SequenceMatcher flags an
    altered link target or swapped image even when the visible text is
    byte-for-byte identical. Tokens are plain text -> still tag-safe.
    """
    parts = [node.get_text(" ", strip=True)]
    for a in node.find_all("a"):
        href = (a.get("href") or "").strip()
        if href:
            parts.append(f"[Link: {href}]")
    for img in node.find_all("img"):
        src = (img.get("src") or "").strip()
        alt = (img.get("alt") or "").strip()
        if src:
            parts.append(f"[Image: {src}]" + (f" [Alt: {alt}]" if alt else ""))
    return " ".join(p for p in parts if p)


def _blocks(raw_html: str) -> list[str]:
    """Flatten a Quill document into an ordered list of block text strings,
    each enriched with link/media structural tokens (see _block_text)."""
    soup = BeautifulSoup(raw_html or "", "html.parser")
    nodes = soup.find_all(_BLOCK_TAGS)
    if not nodes:                              # plain/inline-only content
        text = _block_text(soup)              # still capture top-level links/images
        return [text] if text else []
    out = []
    for n in nodes:
        t = _block_text(n)
        if t:
            out.append(t)
    return out


def _word_diff(old: str, new: str) -> str:
    """Inline word-level diff of two block strings -> safe HTML."""
    o, n = old.split(), new.split()
    sm = SequenceMatcher(a=o, b=n, autojunk=False)
    parts = []
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            parts.append(html.escape(" ".join(o[i1:i2])))
        elif op == "delete":
            parts.append(DEL.format(html.escape(" ".join(o[i1:i2]))))
        elif op == "insert":
            parts.append(INS.format(html.escape(" ".join(n[j1:j2]))))
        elif op == "replace":
            parts.append(DEL.format(html.escape(" ".join(o[i1:i2]))))
            parts.append(INS.format(html.escape(" ".join(n[j1:j2]))))
    return " ".join(p for p in parts if p)


def diff_html(old_html: str, new_html: str) -> dict:
    """Return {'html': <fragment>, 'added': int, 'removed': int}.

    `added`/`removed` are block counts -- used for the SSE summary badge.
    """
    a, b = _blocks(old_html), _blocks(new_html)
    sm = SequenceMatcher(a=a, b=b, autojunk=False)
    rows, added, removed = [], 0, 0
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            for blk in a[i1:i2]:
                rows.append(f'<div class="diff-line py-0.5">{html.escape(blk)}</div>')
        elif op == "delete":
            removed += (i2 - i1)
            for blk in a[i1:i2]:
                rows.append(f'<div class="diff-line py-0.5">{DEL.format(html.escape(blk))}</div>')
        elif op == "insert":
            added += (j2 - j1)
            for blk in b[j1:j2]:
                rows.append(f'<div class="diff-line py-0.5">{INS.format(html.escape(blk))}</div>')
        elif op == "replace":
            added += (j2 - j1); removed += (i2 - i1)
            # Pair blocks positionally for inline word diff; spill extras as add/del.
            for k in range(max(i2 - i1, j2 - j1)):
                ob = a[i1 + k] if i1 + k < i2 else ""
                nb = b[j1 + k] if j1 + k < j2 else ""
                rows.append(f'<div class="diff-line py-0.5">{_word_diff(ob, nb)}</div>')
    return {"html": "\n".join(rows), "added": added, "removed": removed}
```

### New endpoint (`main.py`, beside the other history routes)
```python
@app.get("/api/articles/{article_id}/history/{history_id}/diff")
def get_article_diff(
    article_id: int,
    history_id: int,
    current_admin: models.User = Depends(security.get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Diff a historical snapshot against the article's CURRENT content.
    Returns {'html', 'added', 'removed', 'version_id'} -- admin only."""
    art = db.query(models.Article).filter(models.Article.id == article_id).first()
    if not art:
        raise HTTPException(404, "სტატია ვერ მოიძებნა")
    snap = db.query(models.ArticleHistory).filter(
        models.ArticleHistory.id == history_id,
        models.ArticleHistory.article_id == article_id,
    ).first()
    if not snap:
        raise HTTPException(404, "ისტორიის ვერსია ვერ მოიძებნა")
    import diffing
    result = diffing.diff_html(snap.content, art.content)   # old -> current
    result["version_id"] = snap.version_id
    return result
```
Also extend `get_article_history` (`main.py:2343`) to return `version_id` so the
dropdown can label entries.

---

## 3. Asynchronous Broadcast Loop

### Opt-in notification flag (schema)
Operators should not be toast-spammed for every typo fix. The broadcast is
**opt-in per save** via a transient, non-persisted request field.

`schemas.py` — add to `ArticleCreate` (request-only, so it does **not** leak into
`ArticleResponse`, which inherits `ArticleBase`):
```python
class ArticleCreate(ArticleBase):
    """Request schema for creating/updating a Knowledge Base article."""
    # Transient: when True, PUT /api/articles broadcasts an article_revision
    # SSE event. Never written to the Article row -- popped before persistence.
    notify_operators: bool = False
```
`update_article` (`main.py:2183`) must **pop** it before the setattr loop (the
`Article` model has no such column):
```python
    update_data = article.model_dump()
    notify_operators = update_data.pop("notify_operators", False)   # NEW
    target_departments = update_data.pop("target_departments")
    for key, value in update_data.items():
        setattr(db_article, key, value)
```
Mirror the same pop in `create_article` (`main.py:2115`) so a stray flag on POST
is harmless, and in `app/routers/articles.py` (parity).

### Reuse existing rails
- `broker.publish(event)` (`main.py:703`) is already thread-safe from sync routes
  (`asyncio.run_coroutine_threadsafe`) and Redis-multi-worker-safe.
- `_notify` (`main.py:783`) is the existing minimal helper; we add a sibling for
  revisions rather than overloading it (the revision payload is richer).

### New publisher helper (`main.py`, near `_notify`)
```python
def _notify_revision(article, editor_name: str, summary: dict) -> None:
    """Broadcast that an existing article was edited (best-effort)."""
    broker.publish({
        "type": "article_revision",
        "id": article.id,
        "title": article.title,
        "target_department": getattr(article, "target_department", "All"),
        "version": article.version,
        "editor": editor_name,
        "added": summary.get("added", 0),
        "removed": summary.get("removed", 0),
    })
```

### Hook point (`main.py`, end of `update_article`, after the bump)
Capture old content before the loop overwrites it, then publish **after**
`db.commit()` so we never announce an edit that rolled back:
```python
    old_content = db_article.content            # capture BEFORE the setattr loop
    ...
    db.commit(); db.refresh(db_article)
    ...                                          # existing audit log + cache clears
    # Real-time broadcast is OPT-IN. The history row is always written above;
    # we only ping the SSE channel when the editor ticked "notify operators".
    if notify_operators and db_article.status == "published":
        import diffing
        summary = diffing.diff_html(old_content, db_article.content)
        _notify_revision(db_article, current_admin.name, summary)
    else:
        logger.info(
            "Article %s revised by %s (v%s) -- history saved, no broadcast "
            "(notify_operators=%s, status=%s).",
            db_article.id, current_admin.id, db_article.version,
            notify_operators, db_article.status,
        )
```
The SSE endpoint (`main.py:1283`) already filters by `target_department` / admin
— `article_revision` inherits that gating with no change. When the flag is False
the revision is recorded silently (history row + log line), never touching the
SSE channel.

### SSE event schema (wire contract)
```jsonc
event: article_revision
data: {
  "type": "article_revision",
  "id": 42,                       // article id
  "title": "ინტერნეტ პაკეტები",
  "target_department": "All",     // gating key (existing contract)
  "version": 5,                   // article.version AFTER the edit
  "editor": "ნინო ბერიძე",
  "added": 2,                     // block-level counts from diff_html()
  "removed": 1
}
```

---

## 4. Admin Interface Extension

### Editor — "notify operators" checkbox (producer side)
Add a styled checkbox next to the Save/Update button (form submitted by
`submitArticleForm`, `app-core.js:2008`; submit row lives in the
`create-article-form` markup in `base-layout.html`):
```html
<label for="article-notify-operators"
       class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-zinc-300 select-none cursor-pointer">
  <input type="checkbox" id="article-notify-operators"
         class="h-4 w-4 rounded border-gray-300 text-[#E30613] focus:ring-[#E30613]">
  ოპერატორების შეტყობინება (Real-time Broadcast)
</label>
```
Bind into the payload built at `app-core.js:2055`:
```js
const notifyOperators = document.getElementById('article-notify-operators')?.checked || false;
const payload = { title, content, /* …existing… */, is_draft: false,
                  notify_operators: notifyOperators };
```
The flag rides the existing PUT/POST request (`app-core.js:2073`); no separate
call. It resets with `event.target.reset()` (`app-core.js:2085`) and defaults
unchecked so a broadcast is always deliberate.

### Version list + Quick Look (reviewer side)
Extend the existing revision modal `viewArticleHistory` (`app-core.js:3449`) and
restore flow `restoreArticleVersion` (`app-core.js:3502`). No new page.

1. The history list renders one card per revision with a "ვერსია N" badge
   (`app-core.js:3472`). Add a **"შედარება" (Compare)** button per card next to
   "აღდგენა", and use `version_id` for the badge, falling back to
   `history.length - index` when null.
2. **Quick Look modal** — a lightweight second overlay (separate from the history
   list, non-blocking, list stays open behind it):
```js
async function quickLookDiff(articleId, historyId) {
  const token = Auth.getToken();
  const res = await fetch(`/api/articles/${articleId}/history/${historyId}/diff`,
                         { headers: { 'Authorization': 'Bearer ' + token } });
  if (handleSessionExpiry(res)) return;
  if (!res.ok) { alert('შედარება ვერ ჩაიტვირთა.'); return; }
  const { html, added, removed } = await res.json();
  ensureDiffModal();                       // mirror of ensureHistoryModal()
  document.getElementById('diff-modal-summary').textContent =
      `+${added} / −${removed} ბლოკი შეიცვალა`;
  // SAFE: server output is built from html.escape-d text -> direct injection ok.
  document.getElementById('diff-modal-content').innerHTML = html;
  const m = document.getElementById('diff-modal');
  m.classList.remove('hidden'); m.classList.add('flex');
}
window.quickLookDiff = quickLookDiff;
```
3. The diff fragment ships its own theme-aware Tailwind classes — renders with no
   extra CSS. Modal markup follows the existing `ensureHistoryModal` pattern for
   consistent dark/light + mobile-first styling.

### Live toast (consumer side)
1. Register the new event type in the EventSource loop (`app-core.js:1377`):
   `['news', 'article', 'video', 'broadcast', 'nudge', 'article_revision']`.
2. Handle it in `handleLiveEvent` (`app-core.js:1426`):
```js
} else if (type === 'article_revision') {
  showToast('სტატია განახლდა ✏️',
            `${data.title} — ვერსია ${data.version}`,
            () => navTo('page-info'));
  fetchAndRenderKnowledgeBase(token);   // refresh the KB list in place
}
```

---

## Files to modify (summary)

| File | Change |
|------|--------|
| `models.py:385` | Add `version_id` column to `ArticleHistory` |
| `migrate.py:57` | Append `("article_history","version_id","INTEGER","NULL")` to `_ADDED_COLUMNS` |
| `schemas.py:231` | Add transient `notify_operators: bool = False` to `ArticleCreate` |
| `diffing.py` (new) | `diff_html()` + `_blocks`/`_block_text` (link/media tokens) + `_word_diff` |
| `main.py:2175` | Stamp `version_id` when archiving (update + restore at `:2392`) |
| `main.py:2183` | Pop `notify_operators` before setattr loop; capture `old_content` |
| `main.py:2193` | Compute diff summary + gated `_notify_revision` post-commit |
| `main.py:2115` | Pop `notify_operators` in `create_article` (harmless on POST) |
| `main.py:~785` | Add `_notify_revision` helper |
| `main.py:~2353` | New `GET …/history/{id}/diff`; add `version_id` to history response |
| `app/routers/articles.py:236,266` | Mirror archive/version_id + pop/gate (parity, not live) |
| `base-layout.html` (`create-article-form`) | Add `#article-notify-operators` checkbox next to Save/Update |
| `static/js/app-core.js:1377,1426,2055,3449,3502` | Event type, toast handler, payload flag, Compare button, Quick Look modal |

---

## Verification

1. **Migration idempotency:** run the app once (`uvicorn main:app`); confirm
   `migrate`/`create_all` adds `version_id` without error, then restart to confirm
   no duplicate-column error. On an existing DB,
   `PRAGMA table_info(article_history)` must list `version_id`.
2. **Diff unit check:**
   `python -c "import diffing; print(diffing.diff_html('<p>hello world</p>','<p>hello brave world</p>'))"`
   → expect an `<ins>brave</ins>` run, balanced tags, correct `added/removed`.
   Adversarial tag-split case (`<div><b>x</b></div>` vs `<div>x</div>`) → no broken
   tags. **Link/media:** `<p>see <a href="/old">doc</a></p>` vs
   `<p>see <a href="/new">doc</a></p>` (same text, changed href) → row registers
   as changed via the `[Link: …]` token.
2b. **Opt-in gate:** PUT with `notify_operators:false` → new `ArticleHistory` row
   exists, log line emitted, **no** SSE event reaches a connected operator. Repeat
   with `notify_operators:true` → toast fires.
3. **End-to-end:** content admin edits a published article; a second session
   (operator in the target dept) sees the `article_revision` toast live; open the
   history modal → Compare → Quick Look renders a themed inline diff (light + dark).
4. **Regression:** existing restore still works; `get_article_history` responses
   now include `version_id`.
5. **Redis path:** with `REDIS_URL` set, the event publishes through
   `magti_sse_events`; with Redis down, the in-process fallback still delivers in
   single-worker dev (broker logs the degraded warning).
