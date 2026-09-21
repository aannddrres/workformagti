---
name: access-change
description: Use when changing who may call an endpoint or see a record in the Magti Portal - adding or removing a controller endpoint, changing a require* gate, widening or narrowing a manager's or department's view, changing what a response returns about a person, or adding a permission. Covers the ACCESS_CONTRACT_MATRIX_KA.md obligation and the coverage tests that fail the build when source and contract disagree.
---

# Changing who may see what

Authorization here is **not** annotation-driven. `SecurityConfig` decides only
*whether* a caller is signed in: its chain is deny-by-default,
`.anyRequest().authenticated()`, and the only anonymous paths are the
`ANONYMOUS_*` arrays — login, SSO start, `/api/health` and the actuator probes
(`AnonymousSurfaceTest` pins them). It decides nothing about role, permission
or scope: there is not one `@PreAuthorize` in the module, and every handler
gates itself with a `require*` call. A handler that forgets its gate is open
to every signed-in employee, operators included. Nothing in the framework
catches that — a row of coverage tests does, and they are the reason this has
stayed consistent. Work with them, not around them.

## The obligation

**A gate that moves without `docs/ACCESS_CONTRACT_MATRIX_KA.md` moving in the
same commit fails the build.** That is deliberate: weakening a gate and
updating the row is a reviewable event; weakening it and not updating the row
is how SEC-02 and SEC-03 happened.

The matrix has one row per endpoint, in a seven-column table:

| endpoint | handler | today's gate | capability | scope | PII | note |

Only the first three are checked mechanically. `capability`, `scope` and `PII`
are forward-looking decisions, not facts about today's code — asserting them
would make the contract a mirror of the implementation rather than a
constraint on it.

## The loop

1. Make the change.
2. Update the matrix row — endpoint, handler as `ClassName.methodName`, and
   the gate cell listing every `require*` call in the handler body, sorted and
   comma-joined, or an em dash `—` when there is none.
3. Run the fast suite. It is DB-free and takes seconds:

```bash
cd java-backend && ./mvnw -B test -DexcludedGroups=oracle
```

4. If the change touches data a user can see about *other* people, run the
   Oracle half too — that is where the integration tests live:

```bash
cd java-backend && ./mvnw -B test -Dgroups=oracle
```

## What will fail, and what it means

| Test | Fails when |
|---|---|
| `AccessContractCoverageTest` | An endpoint has no row; a row has no endpoint; a gate or handler name no longer matches the row |
| `EndpointPrincipalCoverageTest` | A handler does not take `@AuthenticationPrincipal User`. Three endpoints are deliberately public and allowlisted; the allowlist is checked for staleness in both directions |
| `EndpointGuardCoverageTest` | A handler's call closure — followed through private helpers via ASM — contains no recognised `require*` guard |
| `PermissionEnforcementCoverageTest` | A `Permission` catalog entry is consulted by nothing. Stops SEC-06 reopening, where three permissions were admin switches that gated nothing |
| `ResponseShapeContractTest` | A response record carrying employee identity gained or renamed a field. SEC-03 was a right gate with the wrong payload shape |
| `AnonymousSurfaceTest`, `DenyByDefaultIntegrationTest` | The unauthenticated surface grew |

A gate helper must be `require` followed by a capital letter. That is what
separates `requireContentAdmin` from the local variable `requiredCount`, which
is not a gate and must not be recorded as one.

## Do not write a second copy of a rule

Each of these is the single implementation of a question that was, or easily
becomes, answered in several places. Read the file before adding a check.

- **`security/ManagerScope.java`** — which users a manager may see. Prefix-aware:
  a manager over `ტექნიკური` sees `ტექნიკური — ჯგუფი 03`. Five sites once
  answered this independently with exact string equality; that is SEC-13.
- **`article/ArticleVisibility.java`** — may this person read this article.
  Shared by the article endpoint and by `/uploads/{filename}` so the two cannot
  disagree. Its reader-facing lifecycle clause is mirrored in
  `angular-frontend/src/app/shared/article-visibility.ts`; the cases both must
  agree on are in `docs/api-contract/article-visibility-cases.json`, run by
  `ArticleVisibilityParityTest` and by the Angular suite. Changing the rule
  means changing both and adding a case.
- **`storage/FileAccessPolicy.java`** — a file is readable when content that
  references it is readable (DEC-P01). Enforcement is live in production
  (`ROLLOUT_FILE_ENTITLEMENT: "true"`); shadow remains the rollback.
- **`util/DepartmentMatcher.java`** — department targeting, including that a
  target of `"All"` matches everyone.

## Two things that are not what they look like

**`is_draft` is not `status='draft'`.** `is_draft` is the personal-autosave
flag and hides a row from everyone but its author — content administrators
included. Editorial state goes in `status`. Getting this wrong once made all
122 imported articles visible to exactly one account.

**Authorization is re-read from the database on every request**
(`JwtAuthenticationFilter`), so a role change or a deactivation takes effect
immediately rather than at the next login. The `tv` claim against
`users.token_version` is log-out-everywhere, not per-session, and that is
deliberate (SEC-14, `V34`).

## Before you finish

If the change alters what a person can see about another person, say so
explicitly in the commit message and check whether
`docs/PRODUCT_OWNER_DECISIONS_KA.md` already settles it. If it does not, and
the answer is a product judgement rather than a bug, ask rather than decide.
