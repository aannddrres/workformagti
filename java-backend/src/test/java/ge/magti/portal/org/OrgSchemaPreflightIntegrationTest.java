package ge.magti.portal.org;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate in front of V37, tested against the database it is a claim about.
 *
 * <p>These assertions cannot be made without Oracle. The preflight's whole
 * job is to predict what {@code ALTER TABLE ... NOT NULL} and
 * {@code CREATE UNIQUE INDEX} would do to this data, and an in-memory double
 * would only confirm that the code agrees with itself.
 *
 * <p>Each blocking case plants exactly the row V37 would choke on and asserts
 * the preflight names the constraint that would have raised the error, so the
 * report tells an operator what to fix rather than that something is wrong.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class OrgSchemaPreflightIntegrationTest {

    @Autowired private OrgSchemaPreflight preflight;
    @Autowired private TeamRepository teamRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private LeadershipAssignmentRepository leadershipAssignmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** leadership_assignments.user_id is a real foreign key, so a leader has to exist. */
    private Long aLeader() {
        User user = new User();
        user.setEmail("schema-preflight-" + System.nanoTime() + "@magti.ge");
        user.setName("Schema preflight");
        user.setRole(Role.MANAGER);
        user.setDepartment("ტექნიკური");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        return userRepository.saveAndFlush(user).getId();
    }

    private Department technical() {
        return departmentRepository.findByStableKey("TECHNICAL").orElseThrow();
    }

    private Team group(String name, Long departmentId, String adId) {
        Team team = new Team();
        team.setName(name);
        team.setStableKey("PF_" + name.hashCode());
        team.setDepartmentId(departmentId);
        team.setAdExternalId(adId);
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private Optional<OrgSchemaPreflight.Violation> finding(String constraint) {
        return preflight.run().violations().stream()
                .filter(v -> v.constraint().equals(constraint))
                .findFirst();
    }

    @Test
    void aSchemaWithNothingPlantedIsCleanAndLetsV37Through() {
        OrgSchemaPreflight.Report report = preflight.run();

        assertTrue(report.clean(), () -> "unexpected findings: " + report.violations());
        assertFalse(report.blocksV37());
    }

    @Test
    void aGroupWithNoDepartmentBlocksTheNotNullConstraint() {
        group("უდეპარტამენტო", null, null);

        OrgSchemaPreflight.Violation violation = finding("teams.department_id NOT NULL").orElseThrow();

        assertEquals(1, violation.rows());
        assertTrue(violation.blocking());
        assertTrue(violation.samples().getFirst().contains("უდეპარტამენტო"),
                "the sample has to name the group, or fixing it needs a second query: " + violation.samples());
        assertTrue(preflight.run().blocksV37());
    }

    @Test
    void twoGroupsWithTheSameNameInOneDepartmentBlockTheCompositeUniqueIndex() {
        Long department = technical().getId();
        group("ღამის ცვლა", department, null);
        group("ღამის ცვლა", department, null);

        OrgSchemaPreflight.Violation violation = finding("uq_teams_department_name").orElseThrow();

        assertEquals(1, violation.rows(), "one colliding pair, not two rows");
        assertTrue(violation.samples().getFirst().contains("ღამის ცვლა x2"));
        assertTrue(preflight.run().blocksV37());
    }

    /**
     * The same name in two different departments is the case V36 dropped the
     * global uq_teams_name for. Reporting it would make the preflight refuse a
     * migration that would have applied.
     */
    @Test
    void theSameNameInTwoDepartmentsIsNotAProblem() {
        List<Department> departments = departmentRepository.findAll();
        group("ღამის ცვლა", departments.get(0).getId(), null);
        group("ღამის ცვლა", departments.get(1).getId(), null);

        assertTrue(finding("uq_teams_department_name").isEmpty());
        assertFalse(preflight.run().blocksV37());
    }

    @Test
    void twoGroupsClaimingOneDirectoryIdBlockTheExternalIdUniqueIndex() {
        Long department = technical().getId();
        group("ჯგუფი ა", department, "CN=Shared,OU=Groups");
        group("ჯგუფი ბ", department, "CN=Shared,OU=Groups");

        OrgSchemaPreflight.Violation violation = finding("uq_teams_ad_external_id").orElseThrow();

        assertEquals(1, violation.rows());
        assertTrue(violation.samples().getFirst().contains("CN=Shared,OU=Groups x2"));
        assertTrue(preflight.run().blocksV37());
    }

    /** Nulls are not duplicates of each other -- a unique index accepts many. */
    @Test
    void groupsWithNoDirectoryIdYetDoNotCollideWithEachOther() {
        Long department = technical().getId();
        group("ჯგუფი გ", department, null);
        group("ჯგუფი დ", department, null);

        assertTrue(finding("uq_teams_ad_external_id").isEmpty());
    }

    /**
     * The verification half. V36's unique index is what makes a second active
     * PRIMARY impossible, so the way to show the preflight is checking the real
     * schema is that the database refuses the row it looks for.
     */
    @Test
    void aSecondActivePrimaryCannotBeCreatedAtAllWhileV36sIndexIsInPlace() {
        Long department = technical().getId();
        Team team = group("ჯგუფი ე", department, null);
        leadershipAssignmentRepository.saveAndFlush(primary(team.getId(), aLeader()));

        assertThrows(RuntimeException.class,
                () -> leadershipAssignmentRepository.saveAndFlush(primary(team.getId(), aLeader())),
                "if this ever stops throwing, uq_leadership_primary_team is gone and the non-blocking "
                        + "check below is the only thing that would notice");

        assertTrue(finding("uq_leadership_primary_team").isEmpty());
    }

    private LeadershipAssignment primary(Long teamId, Long userId) {
        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(userId);
        assignment.setTeamId(teamId);
        assignment.setAssignmentType(AssignmentType.PRIMARY);
        assignment.setActive(true);
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        assignment.setStartedAt(TbilisiTime.now());
        return assignment;
    }
}
