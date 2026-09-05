> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Analytics / Dashboard Stats Audit — Magti Portal

> Code audit of the admin dashboard analytics/statistics engine ahead of the
> SQLite (dev) → PostgreSQL 15 (prod) transition. **Analysis only — no code changed
> by this document.** All line references are against the live app (`main:app` in
> `main.py`); the `app/` routers contain no stats logic.

> **Scoping honesty:** the brief assumes stats queries "materialize ORM objects just to
> count." That is **mostly already handled** — `compliance`, `user-progress`, both
> `team-stats`, `department-stats`, `popular/failed-searches`, and `export_team_stats_pdf`
> use DB-side `func.count` / `func.sum` / `group_by` with explicit, commented N+1
> avoidance. This audit pinpoints the **few real offenders** rather than rubber-stamping
> the premise.

**Stats surface:**
`/api/statistics/{popular-searches,failed-searches,compliance,user-progress,activity,kpi}`,
`/api/admin/stats/team/{id}`, `/api/manager/{team-stats,department-stats}`,
`/api/audit-logs`, `/api/export/team-stats.pdf`.

---

## 📊 Current Implementation & Production Risks (SQLite vs Postgres)

**Lock/MVCC reality (corrects the brief's framing).** The *locking* risk is a **SQLite**
trait — its single-writer database lock means a long analytics read can stall writers.
**Postgres 15 uses MVCC**, so these read-only aggregations don't block writers at all.
Migrating therefore *reduces* the lock risk; the real Postgres cost shifts to **seq scans
+ sort / hash-aggregate on growing tables** (`audit_logs`, `read_statuses`) and the dialect
differences below. Frame the work as "index-supported aggregation," not "lock avoidance."

### R1 — `/api/statistics/activity` materializes the audit log every refresh
`main.py:3917-3941` runs `db.query(AuditLog.timestamp).filter(timestamp >= cutoff).all()`
then buckets in a **Python loop**. `audit_logs` is the fastest-growing table; this pulls
every row from the last 7 days on each dashboard load (it feeds the activity Chart.js).
This is the one query that literally matches the brief's anti-pattern. **Highest priority.**

### R2 — `compliance` groups by the full `articles` row including `content` TEXT
`main.py:2900`: `…group_by(*models.Article.__table__.columns).order_by(desc(func.count(...)))`.
Grouping by every column (to satisfy Postgres' strict `GROUP BY` while selecting whole ORM
objects) makes Postgres hash/sort on the large `content` TEXT column — expensive and
memory-heavy as articles grow. SQLite tolerates loose `GROUP BY`, so this is a
**SQLite-passes / Postgres-slow** trap.

### R3 — No caching on any stats endpoint
`search_cache` / `category_cache` (`InMemoryTTLCache`, `main.py:326` / `373`) are the only
TTL caches and cover search/categories only. KPI / compliance / activity recompute from
scratch on every dashboard refresh — and `InMemoryTTLCache` is **per-worker** under the 4
gunicorn workers anyway, so it wouldn't share a cached value even if applied.

### R4 — Audit filters lean on single-column indexes
`audit_logs` is indexed on `timestamp` (`models.py:322`), `category` (`326`), and
`admin_id` (`ix_audit_logs_admin_id`, recently added). `action` is **unindexed**
(`models.py:319`). The default view (`ORDER BY timestamp DESC LIMIT 100`, `main.py:4201`)
is well-served by the timestamp index, but **filtered** views (`category=…&start=…`,
`user_id=…&start=…`) do a filter-then-sort that a **compound** index would serve directly.

### R5 — Duplicated aggregation logic (maintainability, not speed)
The `read_map[(user_id, target_department)] = count` grouped query is copy-pasted ~5×
(`main.py:2964, 3118, 3229, 3382, 5137`, consumed by `_reading_progress` at `2910`).
Correct and DB-native, but drift-prone; each also does a small
`db.query(RequiredReading).all()` materialization (table is tiny → low cost).

### Explicitly **not** a problem
- `/api/statistics/kpi` (`main.py:3959-3963`) uses four `.count()` calls = four real
  `SELECT count(*)` (no materialization). Only minor (4 round-trips, combinable).
- `/api/audit-logs` is bounded at `LIMIT 100`.
- Chart.js feeds today are small/bounded (7 daily points; top-5; percentages) — no giant
  timeline reaches the browser yet. The risk is the *server-side work* to produce them.

---

## 🚀 Query Speed Optimization Actions (exact SQLAlchemy changes)

### A1 — DB-bucket the activity trend *(highest value)*
Replace the `.all()` + Python loop in `get_activity_trend` (`main.py:3917`) with a grouped
count. Must be **cross-dialect** (SQLite dev / Postgres prod; dialect is detectable via
`engine.dialect.name`, as `migrate.py:62` already does):
```python
from sqlalchemy import func
day = func.date(models.AuditLog.timestamp)            # SQLite
# Postgres: func.date_trunc('day', models.AuditLog.timestamp)
rows = (db.query(day.label("d"), func.count(models.AuditLog.id))
          .filter(models.AuditLog.timestamp >= cutoff_date)
          .group_by(day).all())
counts = {str(d): c for d, c in rows}
# then zero-fill the 7-day window in Python (keeps the empty-day buckets)
```
Wrap the dialect choice in a small `_day_bucket(col)` helper. Returns the same 7-element
shape the chart already consumes — pure backend change, no frontend edit.

### A2 — Stop grouping by `content`
In `get_compliance_statistics` (`main.py:2885-2902`), select `Article.id` + the count,
group by `Article.id` only, then fetch the top-5 rows by id:
```python
top = (db.query(models.Article.id, func.count(models.ReadStatus.id).label("n"))
         .join(...).filter(...).group_by(models.Article.id)
         .order_by(desc("n")).limit(5).all())
ids = [i for i, _ in top]
articles = db.query(models.Article).filter(models.Article.id.in_(ids)).all()
# re-sort `articles` into `ids` order before returning
```
Eliminates hashing the TEXT column; keep the `target_department_rows.any()` guard
(`main.py:2899`) that prevents `ArticleResponse` validation 500s on legacy rows.

### A3 — Compound audit indexes
Add to `AuditLog.__table_args__` (`models.py`) **and** a matching
`CREATE INDEX IF NOT EXISTS` in `migrate.py` (same pattern just used for
`ix_audit_logs_admin_id`), names kept in sync so fresh + existing DBs converge:
- `(category, timestamp DESC)`
- `(admin_id, timestamp DESC)`
- `(action, timestamp DESC)`

These serve the filtered "category + date range, newest first" and per-user audit views
without a separate sort. Low write cost (append-heavy table, but inserts are batched via
the `_log_writer` queue).

### A4 — Redis TTL cache for non-realtime stats
KPI / compliance / activity are historical, not live — cache their JSON in **Redis**
(already wired for SSE, `main.py:~605`) with a short TTL (60–300 s), keyed `stats:kpi`,
`stats:compliance`, `stats:activity:7d`. Invalidate opportunistically on content/reading
writes (the same call sites that already `search_cache.clear()`), or just let the TTL
lapse. Prefer Redis over `InMemoryTTLCache` here so all 4 workers share one cached value.

### A5 — Consolidate the read-count aggregation
Extract the repeated grouped query into one helper —
`read_counts_by_user_dept(db, status="read") -> dict[(uid, dept), int]` — and reuse across
`user-progress`, both `team-stats`, `department-stats`, and `export_team_stats_pdf`.
Quality/consistency win; pairs with the existing `_reading_progress` (`main.py:2910`).

### A6 — *(minor)* collapse the 4 KPI counts
Combine into a single round-trip (`UNION ALL` of scalar counts, or four correlated
`func.count` subqueries in one `select`). Marginal — do only if KPI latency surfaces.

---

## 🎛️ Granular Admin Metrics Roadmap (drill-down logic & data structures)

### G1 — Parameterize the timeline
Extend `/api/statistics/activity` with `?days=N`, `?bucket=hour|day`, and optional
`?category=` / `?action=`, all resolved by the A1 `_day_bucket` helper
(`date_trunc('hour'|'day', …)` on PG; `strftime('%Y-%m-%d %H' | '%Y-%m-%d', …)` on SQLite).
**Standard response contract:** `[{ "bucket": "<iso>", "count": N }]`, always zero-filled
and **server-capped** (reject `days * buckets` over a ceiling) so the chart never receives
an unbounded series.

### G2 — Drill-down dimensions from existing columns (no schema change)
The data already supports breakdown by **department** (`users.department`), **role**
(`users.role`), **status** (`read_statuses.status`), and **audit category/action**. Expose
one generic endpoint —
`GET /api/statistics/breakdown?dimension=department|role|status&metric=reads|activity` —
emitting `func.count(...).group_by(<dimension>)`. One endpoint, several pivots, all DB-side.

### G3 — Daily-active / read-velocity counters
A "reads per day" and "active users per day" series via
`group_by(_day_bucket(read_statuses.read_at))` and
`group_by(_day_bucket(audit_logs.timestamp))` filtered to USER-category actions. Backed by
A3's `(action, timestamp)` index.

### G4 — Schema upgrade **only if** the live queries get slow
A nightly-rolled `daily_stats(date, department, metric, value)` rollup table (populated by a
scheduled job) lets historical dashboards read pre-aggregated rows instead of scanning
`audit_logs` / `read_statuses`. **Recommend deferring** — A1 + A3 + A4 should keep
sub-second latency at ~600 users; a rollup table is the escape hatch for 10×+ growth, not
day-one work. Don't over-build.

---

## Suggested sequencing (low-risk → higher-risk)
1. **A3** (compound audit indexes) + **A1** (activity DB-bucketing) — additive / contained.
2. **A2** (compliance group-by fix) + **A6** (KPI collapse) — query-shape only.
3. **A4** (Redis stats cache) + **A5** (helper consolidation) — touches more call sites.
4. **G1–G3** (granularity) — new endpoints; **G4** deferred until metrics warrant it.

## Verification (when executed)
- `pytest tests/test_resilience.py -q` after any code change.
- **A1 / A2:** assert the endpoints return the **same shape/values** as today on the dev DB
  (snapshot before/after); on Postgres, `EXPLAIN ANALYZE` the activity + compliance queries
  to confirm aggregate/index plans replace seq-scan + sort.
- **A3:** re-run `migrate.py` twice (idempotent); `EXPLAIN` a filtered audit query
  (`category` + date range) before/after.
- **A4:** hit each cached endpoint twice under `gunicorn -w 4`; the second call serves from
  Redis and all workers agree.
