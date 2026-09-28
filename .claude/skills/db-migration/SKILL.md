---
name: db-migration
description: Use when changing the Magti Portal's Oracle schema - adding or altering a table, column, index, constraint or foreign key, or writing a Flyway migration. Covers the numbering, why IF NOT EXISTS guards are forbidden here, the shape-test convention, the 21c-not-23ai constraint, and the cascade-delete rule that caused a 500 once.
---

# Adding a Flyway migration

Migrations live in `java-backend/src/main/resources/db/migration/` and Flyway
owns the schema outright — `ddl-auto` is `validate`, never `update`.

## Numbering

Take the next number after the highest file present. **`V37` does not exist**;
the sequence skips it deliberately, so do not "fill the gap". `V36_1` exists
as a backfill of `V36` — follow that pattern only for a genuine follow-up to a
specific migration, not for ordinary new work.

The highest version is stated in `AGENTS.md` and in `java-backend/AGENTS.md`,
and `DocumentedFactsTest` fails the build if those statements fall behind the
folder. Update them in the same commit.

## No `IF NOT EXISTS` guards

**Migrations here are not individually idempotent, and must not be written as
if they were.** Flyway takes an exclusive lock on `flyway_schema_history`
before applying anything, so simultaneous instances serialise: one applies, the
other sees the recorded version and skips. Plain `CREATE TABLE` and
`ALTER TABLE` are correct.

A guard is worse than redundant. It hides a migration that half-applied, which
is the one case you want to fail loudly.

## Oracle specifics that have bitten

**The container is Oracle XE 21c, not 23ai.** 23ai's native `BOOLEAN` type
breaks `ddl-auto=validate` against this schema's `NUMBER(1)` flag columns. Do
not introduce `BOOLEAN`; use `NUMBER(1)`.

**A local PDB can come back `MOUNTED` after a host restart**, which presents as
a connection failure rather than as a closed database:

```sql
ALTER PLUGGABLE DATABASE ORCLPDB1 OPEN;
```

**Child tables need `ON DELETE CASCADE`.** `DELETE /api/articles/{id}` once
returned 500, and the cause was two child tables — `user_notes` and
`knowledge_feedback` — whose foreign keys lacked it. Read receipts were the
wrong first suspect. When adding a table that references `articles`, `news` or
`users`, decide the delete behaviour explicitly and say which you chose and
why in the migration's comment.

**Timestamps are `OffsetDateTime` at +04:00** via `util/TbilisiTime`. Store
offsets, not naive local times.

## Shape tests

Six migrations carry a companion test asserting their shape — `V36`, `V39`,
`V40`, `V41`, `V43` and `V45MigrationShapeTest`. They exist for migrations whose structure
something else depends on. There is **no Flyway checksum test**, so an edit to
an already-applied migration will not be caught here; it will be caught by
Flyway itself, at startup, in whichever environment applied it first. Never
edit an applied migration — add a new one.

Write a shape test when the new table or column is load-bearing for a rule
elsewhere in the code. Do not write one for routine additions; the existing
six are the precedent for what "load-bearing" means here.

## Verifying

```bash
cd java-backend && ./mvnw -B test -DexcludedGroups=oracle   # shape tests, no DB
cd java-backend && ./mvnw -B test -Dgroups=oracle           # applies it for real
```

The Oracle run is what actually executes the migration — but *which* database
it runs against decides what it proves. With `ORACLE_DB_URL` unset, the suite
first tries the default `localhost:1521/orclpdb1` (`OracleTestcontainer`). On
the development machine a local 19c answers there, so Flyway applies only the
new migration on top of a schema that has been in use for weeks. The throwaway
21c Testcontainer — the whole chain from V1, the only local proof that the
migration applies from empty — starts only when nothing answers.

To prove it from empty on purpose, point `ORACLE_DB_URL`, `ORACLE_DB_USER` and
`ORACLE_DB_PASSWORD` at a fresh schema — the E2E recipe's `MAGTI_QA`, made by
`scripts/qa_schema_create.sql` — or let CI do it: its Oracle job
always starts from an empty XE 21c.

## Data

**No broad retention purge** without an approved retention policy. The deleted
Python stack had a 180-day archive-then-purge; it was never ported, on purpose.
Quiz attempts, read receipts and compliance evidence are explicitly excluded
from any generic purge — they are the evidence the product exists to keep.
