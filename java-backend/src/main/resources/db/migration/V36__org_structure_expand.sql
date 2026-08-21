-- Phase 2 "expand" of the org-access plan (ORG_ACCESS_ARCHITECTURE_PLAN_KA.md §7.2).
--
-- Everything here is ADDITIVE and nullable on existing tables. Nothing in the
-- application reads these columns yet: this migration can deploy on its own,
-- sit unused, and be rolled back by dropping what it created. The tightening
-- -- NOT NULL, the (department_id, name) uniqueness, the external-id
-- uniqueness -- lives in V37 and must NOT ship in the same deployment as the
-- first backfill run, because the backfill is what makes those constraints
-- satisfiable in the first place.
--
-- The local database has zero rows in `teams`, which is NOT evidence that
-- production does. That is the whole reason for the two-release split.
--
-- Flyway migrations here are deliberately not idempotent and carry no
-- IF NOT EXISTS-style guards (CLAUDE.md): Flyway takes an exclusive lock on
-- flyway_schema_history before applying anything, so simultaneous instances
-- serialise -- one applies, the other sees the recorded version and skips.

-- ---------------------------------------------------------------------------
-- 1. departments
-- ---------------------------------------------------------------------------
-- The three official departments are reference data, not fixtures: every
-- department string in `users` today maps to one of them, and
-- DepartmentBuckets.WHITELIST already hardcodes exactly these three names.
-- They are seeded here so the mapper has something to map to; the five groups
-- per department are NOT seeded here (they are dev/test fixtures and belong to
-- an explicit seeder -- in production they come from AD).
--
-- stable_key is ASCII and internal: AD's own identifier arrives later in
-- ad_external_id, and until IT answers QUESTIONS_FOR_IT.md §10 we do not know
-- what shape it takes. Keying on the Georgian display name instead would tie
-- every foreign key to a string a rename could change, which is exactly what
-- the plan requires group and department identity NOT to do.
CREATE TABLE departments (
    id             NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stable_key     VARCHAR2(50 CHAR) NOT NULL,
    name           VARCHAR2(200 CHAR) NOT NULL,
    ad_external_id VARCHAR2(200 CHAR),
    is_active      NUMBER(1) DEFAULT 1 NOT NULL,
    sort_order     NUMBER DEFAULT 0 NOT NULL,
    synced_at      TIMESTAMP(6),
    CONSTRAINT uq_departments_stable_key UNIQUE (stable_key),
    CONSTRAINT uq_departments_name       UNIQUE (name),
    CONSTRAINT uq_departments_ad_id      UNIQUE (ad_external_id),
    CONSTRAINT ck_departments_active     CHECK (is_active IN (0, 1))
);

-- The names must stay byte-identical to DepartmentBuckets.WHITELIST and to the
-- prefixes DepartmentMatcher.splitGroup produces, or the mapper silently maps
-- nobody. DepartmentMatcherTest asserts those codepoints directly.
INSERT INTO departments (stable_key, name, sort_order) VALUES ('TECHNICAL',   'ტექნიკური',    1);
INSERT INTO departments (stable_key, name, sort_order) VALUES ('INFORMATION', 'საინფორმაციო', 2);
INSERT INTO departments (stable_key, name, sort_order) VALUES ('OFFICE',      'ოფისი',        3);

-- ---------------------------------------------------------------------------
-- 2. teams gains a department, an AD identity and sync metadata
-- ---------------------------------------------------------------------------
-- uq_teams_name has to go: it is global, so "ჯგუფი 1" could exist once across
-- the whole company. The target model has five groups per department, and
-- group names repeat between departments by design.
--
-- Between this migration and V37 there is therefore NO uniqueness on team
-- names at all. Two things close that window rather than one:
--   * POST /api/teams already returns 403 (group identity is directory-owned),
--     so nothing can create a colliding row through the product; and
--   * V37 runs a preflight that refuses to apply while duplicates exist.
-- Neither alone is enough -- the endpoint could be re-opened, and a preflight
-- that finds duplicates only tells you about them after the fact.
ALTER TABLE teams DROP CONSTRAINT uq_teams_name;

ALTER TABLE teams ADD (
    department_id  NUMBER,
    stable_key     VARCHAR2(50 CHAR),
    ad_external_id VARCHAR2(200 CHAR),
    is_active      NUMBER(1) DEFAULT 1 NOT NULL,
    synced_at      TIMESTAMP(6),
    CONSTRAINT fk_teams_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT ck_teams_active     CHECK (is_active IN (0, 1))
);

CREATE INDEX ix_teams_department ON teams (department_id);

-- ---------------------------------------------------------------------------
-- 3. leadership_assignments
-- ---------------------------------------------------------------------------
-- Replaces two things that answer "who leads whom" badly today: users.role ==
-- MANAGER (a role is not a scope, and one person may lead several groups) and
-- users.manager_id (unused -- zero rows populated).
--
-- Scope is two nullable foreign keys, not a polymorphic (scope_type, scope_id)
-- pair. The polymorphic form cannot be checked by the database at all: it is
-- the same soft-reference shape required_readings.item_type/item_id uses, and
-- a wrong id there is only discovered when something fails to resolve it. Two
-- real FKs plus a CHECK that exactly one is set keeps referential integrity,
-- and scope_type becomes derived rather than stored.
--
-- The CHECK counts with CASE rather than comparing IS NULL results. Oracle 19c
-- has no SQL boolean type (that is 23c), so `(a IS NULL) <> (b IS NULL)` --
-- the form this constraint takes on PostgreSQL -- does not parse here.
--
-- UNLIKE teams and users above, this table is new and empty, so its
-- constraints go in NOW rather than in V37. That is not an inconsistency with
-- the staged rollout: staging exists because those two tables hold production
-- data of unknown shape. Here it would actively hurt -- the backfill writes
-- into this table between V36 and V37, and without the unique indexes it could
-- persist two active PRIMARY leaders for one group and only discover it when
-- V37 failed to apply. Constraints first means the backfill fails fast, on the
-- row that is wrong, with the collision still in hand.
CREATE TABLE leadership_assignments (
    id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         NUMBER NOT NULL,
    department_id   NUMBER,
    team_id         NUMBER,
    assignment_type VARCHAR2(20 CHAR) NOT NULL,
    is_active       NUMBER(1) DEFAULT 1 NOT NULL,
    started_at      TIMESTAMP(6) NOT NULL,
    ended_at        TIMESTAMP(6),
    created_by      NUMBER,
    source          VARCHAR2(20 CHAR) DEFAULT 'MANUAL' NOT NULL,
    CONSTRAINT fk_leadership_user       FOREIGN KEY (user_id)       REFERENCES users (id),
    CONSTRAINT fk_leadership_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_leadership_team       FOREIGN KEY (team_id)       REFERENCES teams (id),
    CONSTRAINT fk_leadership_created_by FOREIGN KEY (created_by)    REFERENCES users (id),
    CONSTRAINT ck_leadership_one_scope CHECK (
        (CASE WHEN department_id IS NULL THEN 0 ELSE 1 END)
      + (CASE WHEN team_id       IS NULL THEN 0 ELSE 1 END) = 1),
    CONSTRAINT ck_leadership_type   CHECK (assignment_type IN ('PRIMARY', 'ACTING')),
    CONSTRAINT ck_leadership_active CHECK (is_active IN (0, 1)),
    CONSTRAINT ck_leadership_source CHECK (source IN ('MANUAL', 'BACKFILL', 'AD_SYNC'))
);

-- "At most one active PRIMARY leader per scope", twice -- once per scope
-- column -- because the scope lives in two columns now.
--
-- Oracle has no partial index, so the predicate goes inside the indexed
-- expression: a row that is not an active PRIMARY evaluates to NULL, and
-- Oracle omits a row from a unique index when every indexed expression is
-- NULL. ACTING assignments are therefore unconstrained here on purpose -- a
-- scope may have several acting leaders, and one person may act for several
-- scopes (plan §2).
--
-- This is also why is_active is NOT NULL: on a nullable column `is_active = 1`
-- would evaluate to NULL for a null row, the CASE would return NULL, and the
-- row would drop out of the index silently -- uniqueness quietly not enforced
-- for exactly the rows whose state is least well defined.
CREATE UNIQUE INDEX uq_leadership_primary_dept ON leadership_assignments (
    CASE WHEN is_active = 1 AND assignment_type = 'PRIMARY' THEN department_id END);

CREATE UNIQUE INDEX uq_leadership_primary_team ON leadership_assignments (
    CASE WHEN is_active = 1 AND assignment_type = 'PRIMARY' THEN team_id END);

CREATE INDEX ix_leadership_user_active ON leadership_assignments (user_id, is_active);
CREATE INDEX ix_leadership_team_active ON leadership_assignments (team_id, is_active);
CREATE INDEX ix_leadership_dept_active ON leadership_assignments (department_id, is_active);

-- ---------------------------------------------------------------------------
-- 4. permission provenance
-- ---------------------------------------------------------------------------
-- users.permissions is a flat JSON list, so it cannot say whether a permission
-- is present because the role grants it or because an administrator granted it
-- to this person. That distinction is the whole point of "one role + per-user
-- permissions": without it, changing someone's role has to either wipe their
-- explicit grants or keep grants their new role never had, and both are wrong.
--
-- INHERIT is deliberately NOT a stored state. The absence of a row IS inherit,
-- so "revert to the role default" is a DELETE and cannot drift from the role's
-- own definition. Storing it would create a third state that has to be kept in
-- agreement with Permission.defaultsFor(...) forever.
--
-- users.permissions stays as-is for now; Phase 6 reads this table and treats
-- that column as the legacy source. Nothing is migrated here.
CREATE TABLE user_permission_overrides (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    NUMBER NOT NULL,
    permission VARCHAR2(50 CHAR) NOT NULL,
    state      VARCHAR2(10 CHAR) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    updated_by NUMBER,
    CONSTRAINT fk_perm_override_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_perm_override_by   FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT uq_perm_override      UNIQUE (user_id, permission),
    CONSTRAINT ck_perm_override_state CHECK (state IN ('ALLOW', 'DENY'))
);

-- ---------------------------------------------------------------------------
-- 5. users: compliance override and an optimistic lock
-- ---------------------------------------------------------------------------
-- compliance_override is three-valued and NULL is the normal case: NULL means
-- "whatever ComplianceEligibilityService decides", 1 forces the person in, 0
-- forces them out. A two-valued column would have to be initialised to the
-- policy's current answer for every row, which freezes today's policy as
-- explicit data and makes a later policy change a no-op for everyone.
--
-- lock_version follows V33's precedent exactly. Deliberately NOT reusing
-- token_version: that one is application state whose increments mean "log out
-- everywhere" (SEC-14), and letting Hibernate bump it on every save would log
-- users out whenever an administrator edited their row.
ALTER TABLE users ADD (
    compliance_override NUMBER(1),
    lock_version        NUMBER DEFAULT 0 NOT NULL,
    CONSTRAINT ck_users_compliance_override CHECK (compliance_override IN (0, 1))
);
