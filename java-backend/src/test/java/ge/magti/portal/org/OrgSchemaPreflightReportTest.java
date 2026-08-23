package ge.magti.portal.org;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-gate logic, which is where reading this report can go wrong.
 *
 * <p>"Clean" and "may V37 run" are different questions and the report answers
 * both. A finding that V36 already prevents does not hold the migration -- V37
 * does not create that constraint -- but it must not be rounded down to
 * nothing either: it means a constraint this schema is supposed to have is
 * missing, which is a worse problem than the ones V37 is about, in a different
 * place.
 */
class OrgSchemaPreflightReportTest {

    private static OrgSchemaPreflight.Violation violation(String constraint, boolean blocking) {
        return new OrgSchemaPreflight.Violation(constraint, "detail", 1, List.of("sample"), blocking);
    }

    @Test
    void nothingFoundIsCleanAndOpensTheGate() {
        OrgSchemaPreflight.Report report = new OrgSchemaPreflight.Report(List.of());

        assertTrue(report.clean());
        assertFalse(report.blocksV37());
    }

    @Test
    void aConstraintV37WouldCreateHoldsTheMigration() {
        OrgSchemaPreflight.Report report = new OrgSchemaPreflight.Report(
                List.of(violation("uq_teams_department_name", true)));

        assertFalse(report.clean());
        assertTrue(report.blocksV37());
    }

    @Test
    void aConstraintV36AlreadyCreatedIsReportedWithoutHoldingTheMigration() {
        OrgSchemaPreflight.Report report = new OrgSchemaPreflight.Report(
                List.of(violation("uq_leadership_primary_team", false)));

        assertFalse(report.blocksV37(), "V37 does not create this index, so it cannot fail on it");
        assertFalse(report.clean(), "but a missing V36 constraint must never read as a clean schema");
    }

    @Test
    void oneBlockingFindingAmongNonBlockingOnesStillHoldsTheMigration() {
        OrgSchemaPreflight.Report report = new OrgSchemaPreflight.Report(List.of(
                violation("uq_leadership_primary_dept", false),
                violation("teams.department_id NOT NULL", true),
                violation("uq_leadership_primary_team", false)));

        assertTrue(report.blocksV37());
    }
}
