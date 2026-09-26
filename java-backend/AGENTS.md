# java-backend — notes for coding agents

Spring Boot 4.1.0 on Java 21, Maven wrapper, Oracle via Flyway. Root package
`ge.magti.portal`. The product, the cross-cutting rules and how to run the
whole stack are in the repository root `AGENTS.md`.

## Commands

```bash
./mvnw -B test -DexcludedGroups=oracle
```

The fast, database-free half, and where to iterate. `-Dgroups=oracle` is the
other half. There are no Maven profiles — the split is entirely by JUnit tag,
and `@RequiresOracle` starts a Testcontainer **only** when `ORACLE_DB_URL` is
unset and nothing answers at the configured URL. Point it at a running
instance and it is used instead, which is far faster. On Windows, `.\mvnw.cmd`.

## Authorization is not annotation-driven

`SecurityConfig` decides only *whether* a caller is signed in: the chain is
deny-by-default (`.anyRequest().authenticated()`), and the anonymous paths are
exactly the `ANONYMOUS_*` arrays, pinned by `AnonymousSurfaceTest`. It decides
nothing about role, permission or scope — there is not one `@PreAuthorize` in
the module, and every handler gates itself with a `require*` call. A forgotten
gate is open to every signed-in employee. Nothing in the framework catches
that — the coverage tests below are the enforcement, and they are the pattern
to extend rather than replace.

| Test | Fails when |
|---|---|
| `security/AccessContractCoverageTest` | An endpoint has no row in `docs/ACCESS_CONTRACT_MATRIX_KA.md`, a row has no endpoint, or a gate changed without the document changing with it |
| `web/EndpointPrincipalCoverageTest` | A handler does not take `@AuthenticationPrincipal User`. Three endpoints are deliberately public and allowlisted |
| `web/EndpointGuardCoverageTest` | A handler's call closure, followed through private helpers via ASM, contains no `require*` guard |
| `domain/PermissionEnforcementCoverageTest` | A `Permission` catalog entry is consulted by nothing. SEC-06 was three permissions rendered as admin switches that gated nothing |
| `web/ResponseShapeContractTest` | A response record carrying employee identity gained or renamed a field. SEC-03 was a right gate with the wrong payload shape |
| `OracleTagCoverageTest` | A `@SpringBootTest` lacks `@RequiresOracle`, which would break the CI unit/integration split |
| `config/RolloutSwitchWiringTest` | A `ROLLOUT_*` switch is not described in `docs/ROLLOUT_ROLLBACK_KA.md` |
| `docs/DocumentedFactsTest` | A version or migration number stated in an agent-facing document no longer matches the build |
| `docs/DocsIndexCoverageTest` | A file under `docs/` is missing from `docs/README.md`, or a link there does not resolve |
| `article/ArticleVisibilityParityTest` | The Java visibility rule and its Angular mirror disagree on a case in `docs/api-contract/article-visibility-cases.json` |
| `article/ArticleVisibilityDraftTest` | `ArticleVisibility` stops hiding another author's `is_draft` row from any caller, including administrators, or starts hiding the author's own |
| `web/PrivateDraftIsolationIntegrationTest` | Another author's `is_draft` article is reachable again through a change, bulk, evidence, list, search, cache or assignment endpoint, for a content admin, a system admin or a `content.manage` holder (PO-34, D2) |
| `web/ArticleRequestDraftConsistencyTest` | Create/update accepts `is_draft: true` beside a reader-visible status again |
| `util/DepartmentVisibilityTargetsTest` | A seventh place builds `List.of(user.getDepartment(), …)` inline — it throws on a null department |
| `web/ControllerGuardConsolidationTest` | A controller re-declares `requireAuthenticated` or `requireContentManage`, or the `requireSystemAdmin` inventory shifts |

Each carries a test that guards itself against passing vacuously. Six
`V*MigrationShapeTest` classes (V36, V39, V40, V41, V43, V45) pin the shape of
specific migrations; there is no Flyway **checksum** test, and no ArchUnit.

## Migrations

Next is `V51`. `V37` does not exist — the numbering skips it deliberately, so
do not fill the gap. A new migration also bumps `EXPECTED_FLYWAY_VERSION` in
`scripts/presentation/common.py`: the demo and UAT seeders demand that exact
version, and `DocumentedFactsTest` fails if the two drift.

Three Oracle facts that cost time to rediscover. The container is XE **21c**,
not 23ai: 23ai's native `BOOLEAN` breaks `ddl-auto=validate` against this
schema's `NUMBER(1)` flags. A local PDB can come back `MOUNTED` after a host
restart, presenting as a connection failure — `ALTER PLUGGABLE DATABASE
ORCLPDB1 OPEN;`. And if a delete starts returning 500, look for a child table
whose foreign key lacks `ON DELETE CASCADE` before suspecting the handler;
that was the real cause once, with read receipts as the wrong first suspect.

Flyway's auto-configuration moved to its own module (`spring-boot-flyway`) in
Boot 4 — relevant if you touch Flyway wiring.

## Never

- **Never edit a migration that has been applied.** Add a new one. No checksum
  test catches it here; Flyway does, at startup, in whichever environment
  applied it first.
- **Never write `IF NOT EXISTS`-style guards** in a migration. A 2026-09-24
  concurrent start on Oracle XE 21c interleaved V1–V50 between two JVMs
  (25 migrations each, 50 unique successful history rows). Both became ready,
  but whole-run serialization is not a safe assumption. A guard can hide a
  half-applied migration, which is the case that should fail visibly.
- **Never add `@PreAuthorize`.** This module gates in handler bodies, and the
  coverage tests above assume that.
- **Never re-declare `requireAuthenticated` or `requireContentManage` in a
  controller.** They live in `web/Guards.java`; they were once written 16 and 7
  times over. `requireSystemAdmin` is still nine local copies with four
  different denial messages — that is known, recorded in
  `ControllerGuardConsolidationTest`, and waiting on a decision about what a
  refused caller should be told, not on someone noticing.
- **Never set `is_draft` from a status, or a status from `is_draft`.** They are
  independent columns and the request boundary already refuses the one
  combination that cannot mean anything (`ArticleRequest`). `is_draft` hides a
  row from everyone but its author, administrators included.
- **Never move a gate without editing `docs/ACCESS_CONTRACT_MATRIX_KA.md` in
  the same commit.** The build stops you, but knowing why saves the argument.
