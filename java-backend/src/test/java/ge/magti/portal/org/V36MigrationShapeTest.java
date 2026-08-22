package ge.magti.portal.org;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards on V36 that do not need a database.
 *
 * <p><b>This is not a substitute for applying the migration.</b> Only real
 * Oracle can say whether V36 runs; {@code OracleRoundTripTest} and the
 * integration suite do that. What this catches is the narrower class of
 * mistake that survives review because it looks right in another dialect --
 * the reason each assertion exists is written next to it.
 */
class V36MigrationShapeTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static String v36() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V36__org_structure_expand.sql"));
    }

    /**
     * V36 explains itself at length, and several of those comments quote the
     * very constructs these tests forbid -- the PostgreSQL spelling of the XOR
     * check, and CLAUDE.md's rule about idempotency guards. Matching the raw
     * file therefore fails on the prose that documents the decision rather
     * than on any SQL. These assertions are about statements, so they read
     * statements.
     */
    private static String v36Sql() throws IOException {
        return v36().replaceAll("--[^\n]*", "");
    }

    private static String permissionBackfill() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V36_1__legacy_permission_override_backfill.sql"));
    }

    /**
     * Oracle 19c has no SQL boolean type, so {@code (a IS NULL) <> (b IS NULL)}
     * -- the natural PostgreSQL spelling of "exactly one of these" -- does not
     * parse. It is also the spelling anyone reaching for this constraint will
     * write first.
     */
    @Test
    void theOneScopeCheckUsesCaseArithmeticNotBooleanComparison() throws IOException {
        String sql = v36Sql();

        assertTrue(sql.contains("ck_leadership_one_scope"), "the XOR constraint must exist");
        assertTrue(Pattern.compile("CASE WHEN department_id IS NULL THEN 0 ELSE 1 END").matcher(sql).find(),
                "the scope CHECK must count with CASE");
        assertFalse(Pattern.compile("IS NULL\\s*\\)?\\s*<>").matcher(sql).find(),
                "comparing two IS NULL results needs a boolean type, which Oracle 19c does not have");
    }

    /**
     * Two indexes, not one: the scope lives in two columns since the
     * polymorphic pair was rejected, and a single index over both would make
     * one PRIMARY per (department, team) combination rather than per scope.
     */
    @Test
    void bothPrimaryLeaderUniqueIndexesExistAndAreFunctionBased() throws IOException {
        String sql = v36Sql();

        for (String column : List.of("department_id", "team_id")) {
            assertTrue(Pattern.compile(
                            "CREATE UNIQUE INDEX \\w+ ON leadership_assignments \\(\\s*"
                                    + "CASE WHEN is_active = 1 AND assignment_type = 'PRIMARY' THEN " + column + " END")
                            .matcher(sql).find(),
                    "Oracle has no partial index, so the predicate belongs inside the indexed expression for " + column);
        }
    }

    /**
     * The index above relies on {@code is_active = 1} being false rather than
     * unknown. On a nullable column it would evaluate to NULL, the CASE would
     * return NULL, and Oracle would drop the row from the unique index -- so
     * uniqueness would quietly stop applying to exactly the rows whose state
     * is least well defined.
     */
    @Test
    void isActiveIsNotNullableOnLeadershipAssignments() throws IOException {
        assertTrue(v36Sql().contains("is_active       NUMBER(1) DEFAULT 1 NOT NULL"),
                "a nullable is_active silently disables the primary-leader uniqueness");
    }

    /**
     * The window this opens is real and is closed by two other things (the 403
     * on team creation, and V37's preflight). Pinned so that dropping the
     * constraint stays a deliberate act with those mitigations in view.
     */
    @Test
    void theGlobalTeamNameUniquenessIsDroppedHereAndNotReplacedYet() throws IOException {
        String sql = v36Sql();

        assertTrue(sql.contains("ALTER TABLE teams DROP CONSTRAINT uq_teams_name"));
        assertFalse(sql.contains("uq_teams_dept_name"),
                "the replacement uniqueness belongs to V37, after the backfill has populated department_id");
    }

    /** CLAUDE.md: Flyway serialises on its own lock, so guards here would only hide a real conflict. */
    @Test
    void noIdempotencyGuardsAreUsed() throws IOException {
        assertFalse(v36Sql().toUpperCase().contains("IF NOT EXISTS"),
                "Flyway migrations here are deliberately not idempotent");
    }

    /**
     * Phase 2's contract is "schema expand, behavior unchanged". Every column
     * added to an existing table must therefore be nullable or defaulted --
     * a bare NOT NULL would fail against production rows on the way in.
     */
    @Test
    void everyColumnAddedToAnExistingTableIsNullableOrDefaulted() throws IOException {
        String sql = v36Sql();

        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.contains("NOT NULL") || trimmed.startsWith("--") || trimmed.startsWith("CONSTRAINT")) {
                continue;
            }
            if (isInsideAlterTable(sql, line)) {
                assertTrue(trimmed.contains("DEFAULT"),
                        "column added to an existing table without a DEFAULT: " + trimmed);
            }
        }
    }

    /** V37 must not ride along in the same deployment as the first backfill run. */
    @Test
    void v37IsNotShippedYet() throws IOException {
        try (var files = Files.list(MIGRATIONS)) {
            assertEquals(List.of(), files
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.startsWith("V37"))
                            .sorted()
                            .toList(),
                    "V37 tightens constraints the backfill has not satisfied yet; shipping both at once is the "
                            + "failure the two-release split exists to prevent");
        }
    }

    @Test
    void legacyPermissionBackfillStoresOnlyDifferencesFromRoleDefaults() throws IOException {
        String sql = permissionBackfill();

        assertTrue(sql.contains("legacy_grants <> role_grants"));
        assertTrue(sql.contains("WHEN legacy_grants = 1 THEN 'ALLOW' ELSE 'DENY'"));
        assertTrue(sql.contains("MERGE INTO user_permission_overrides"));
        assertTrue(sql.contains("WHEN NOT MATCHED THEN INSERT"), "an existing explicit override must win");
        assertFalse(sql.contains("SELECT 'content.manage'"), "content.manage had no legacy decision to migrate");
        assertFalse(sql.contains("SELECT 'articles.view'"), "removed no-op permissions must stay removed");
        assertFalse(sql.contains("SELECT 'users.manage'"), "removed no-op permissions must stay removed");
    }

    /**
     * The one permission whose absence from {@code users.permissions} means
     * "this column never carried the decision", not "it was denied".
     *
     * <p>Python spelled it {@code system:audit} with a colon so it would live
     * in the separate role_permissions catalog (security.py:376-381), so
     * DEFAULT_PERMISSIONS_BY_ROLE never wrote the dotted string here for any
     * role. Migrating the DENY direction would close /api/audit-logs and
     * can_view_audit_log for every manager and content admin carrying
     * Python-era defaults -- permanently, since an explicit DENY outranks the
     * role default.
     *
     * <p>Asserted against the SQL rather than a database on purpose: a local
     * Oracle seeded by the Java app carries the dotted string and agrees, so
     * running the migration there proves nothing about this case.
     */
    @Test
    void theBackfillMigratesOnlyTheAllowDirectionOfSystemAudit() throws IOException {
        String sql = permissionBackfill();

        assertTrue(sql.contains("SELECT 'system.audit'"),
                "an explicit pre-Phase-6 grant of system.audit is still evidence, and must survive as ALLOW");
        assertTrue(sql.contains("AND NOT (permission = 'system.audit' AND legacy_grants = 0)"),
                "absence of system.audit predates the column carrying it, so it must not become a DENY");
    }

    /** Crude but sufficient: the two ALTER ... ADD blocks are the only places existing tables gain columns. */
    private static boolean isInsideAlterTable(String sql, String line) {
        int at = sql.indexOf(line);
        int lastAlter = sql.lastIndexOf("ALTER TABLE", at);
        int lastCreate = sql.lastIndexOf("CREATE TABLE", at);
        return lastAlter > lastCreate;
    }
}
