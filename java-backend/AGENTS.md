# java-backend — notes for coding agents

Spring Boot 4.1.0 on Java 21, Maven wrapper, Oracle via Flyway. Root package
`ge.magti.portal`. See the repository root `AGENTS.md` for the product, the
cross-cutting rules and how to run the whole stack.

## The test loop

```bash
./mvnw -B test -DexcludedGroups=oracle
```

That is the fast, database-free half and where you should iterate. The other
half needs Oracle:

```bash
./mvnw -B test -Dgroups=oracle
```

There are no Maven profiles — the split is entirely by JUnit tag. `@RequiresOracle`
is `@Tag("oracle")` plus `@Import(OracleTestcontainer.class)`; the container
starts **only** when `ORACLE_DB_URL` is unset and nothing answers at the
configured URL. Point `ORACLE_DB_URL` at a running instance and it is used
instead, which is much faster.

On Windows use `.\mvnw.cmd`. Source encoding is pinned to UTF-8 on purpose —
there are Georgian literals in the source.

## Packages

`web` is by far the largest (28 controllers plus their request/response
records). `domain` holds JPA entities, `repository` the Spring Data
interfaces. The rest are small and single-purpose: `security`, `export`,
`stats`, `article`, `content`, `compliance`, `org`, `storage`, `quiz`,
`audit`, `search`, `reminder`, `news`, `diff`, `config`, `video`,
`announcement`, `util`.

Authorization is **not** annotation-driven. `SecurityConfig` is
`anyRequest().permitAll()` and there is not one `@PreAuthorize` in the module;
every handler gates itself with a `require*` call. That is why the coverage
tests below exist — they are the enforcement.

## The guardrail tests

These read the source (or the bytecode, or a document) and fail the build on a
mismatch. They are the reason this codebase has stayed consistent, and they
are the pattern to extend rather than replace.

| Test | Enforces |
|---|---|
| `security/AccessContractCoverageTest` | Every `@*Mapping` has a row in `docs/ACCESS_CONTRACT_MATRIX_KA.md`, every row still matches an endpoint, and no gate changed without the document changing with it. A fourth test guards the parser itself so the other three cannot pass vacuously. |
| `web/EndpointPrincipalCoverageTest` | Every handler takes `@AuthenticationPrincipal User`. Carries a three-entry allowlist of deliberately public endpoints, checked for staleness in both directions. |
| `web/EndpointGuardCoverageTest` | Every handler's call closure — followed through private helpers via ASM — contains at least one recognised `require*` guard. |
| `domain/PermissionEnforcementCoverageTest` | Every entry in the `Permission` catalog is actually consulted by a `hasPermission` call. Stops SEC-06 reopening, where three permissions were admin switches consulted by nothing. |
| `web/ResponseShapeContractTest` | Pins the exact JSON key set of every response record carrying employee identity. Adding or renaming a field fails the build. SEC-03 was a right gate with the wrong payload shape. |
| `OracleTagCoverageTest` | Every `@SpringBootTest` carries `@RequiresOracle`, so the CI unit/integration split stays honest. |
| `config/RolloutSwitchWiringTest` | Every `ROLLOUT_*` switch is described in `docs/ROLLOUT_ROLLBACK_KA.md`. |
| `docs/DocumentedFactsTest` | Versions and counts stated in `AGENTS.md` and `README.md` still match `pom.xml`, `package.json` and the migration folder. |
| `docs/DocsIndexCoverageTest` | Every file under `docs/` has a row in `docs/README.md`, every row points at a file that exists, and every relative link in `docs/` resolves. |
| `article/ArticleVisibilityParityTest` | The Java rule and the Angular mirror agree on every case in `docs/api-contract/article-visibility-cases.json`. |

Also present: five `V*MigrationShapeTest` classes asserting the shape of
specific migrations. There is no Flyway **checksum** test, and no ArchUnit.

## Adding a migration

Next is `V49`. `V37` does not exist — the numbering skips it, deliberately.

Migrations must **not** carry `IF NOT EXISTS`-style guards: Flyway takes an
exclusive lock on `flyway_schema_history` before applying anything, so
simultaneous instances serialise. Plain `CREATE TABLE` / `ALTER TABLE` is
correct.

Two Oracle-specific traps worth knowing. The CI service container is Oracle XE
**21c**, not 23ai — 23ai's native `BOOLEAN` breaks `ddl-auto=validate` against
this schema's `NUMBER(1)` flags. And a local PDB can come back `MOUNTED` after
a host restart, which looks like a connection failure:
`ALTER PLUGGABLE DATABASE ORCLPDB1 OPEN;`.

If a delete starts returning 500, check for a child table whose foreign key
lacks `ON DELETE CASCADE` before suspecting the handler — that was the real
cause once, and read receipts were the wrong first suspect.

## Boot 4.1 notes

Flyway auto-configuration moved to its own module (`spring-boot-flyway`) in
Boot 4. `jackson-databind` is a compile-scope dependency on purpose, for
`PermissionsConverter`. The CycloneDX plugin is bound to `package` and writes
`target/bom.json`, which the `supply-chain` CI job scans with Trivy.
