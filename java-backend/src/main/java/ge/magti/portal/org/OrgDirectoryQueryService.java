package ge.magti.portal.org;

import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Team;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * One fail-loud seam for complete-result organization reference snapshots.
 * Callers either receive the whole result (up to {@value #MAX_ROWS}) or the
 * request fails; this module never turns an organization/access answer into a
 * silently truncated partial result.
 */
@Service
public class OrgDirectoryQueryService {

    public static final int MAX_ROWS = 1_000;

    private final DepartmentRepository departmentRepository;
    private final TeamRepository teamRepository;
    private final LeadershipAssignmentRepository assignmentRepository;

    public OrgDirectoryQueryService(
            DepartmentRepository departmentRepository,
            TeamRepository teamRepository,
            LeadershipAssignmentRepository assignmentRepository) {
        this.departmentRepository = departmentRepository;
        this.teamRepository = teamRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional(readOnly = true)
    public List<Department> listDepartmentsWithinLimit() {
        return enforceWithinLimit(departmentRepository.findAll(idPage()).getContent());
    }

    @Transactional(readOnly = true)
    public List<Department> listActiveDepartmentsWithinLimit() {
        return enforceWithinLimit(departmentRepository.findByActiveTrueOrderBySortOrder(idPage()));
    }

    @Transactional(readOnly = true)
    public List<Team> listTeamsWithinLimit() {
        return enforceWithinLimit(teamRepository.findAllByOrderByName(idPage()));
    }

    @Transactional(readOnly = true)
    public List<Team> listTeamsInDepartmentsWithinLimit(Collection<Long> departmentIds) {
        if (departmentIds.isEmpty()) {
            return List.of();
        }
        return enforceWithinLimit(teamRepository.findByDepartmentIdIn(departmentIds, namePage()));
    }

    @Transactional(readOnly = true)
    public List<LeadershipAssignment> listAssignmentsWithinLimit() {
        return enforceWithinLimit(assignmentRepository.findAll(idPage()).getContent());
    }

    @Transactional(readOnly = true)
    public List<LeadershipAssignment> listActiveAssignmentsWithinLimit() {
        return enforceWithinLimit(assignmentRepository.findByActiveTrue(idPage()));
    }

    /** Complete active assignment set for one caller's authorization scope. */
    @Transactional(readOnly = true)
    public List<LeadershipAssignment> listActiveAssignmentsForUserWithinLimit(Long userId) {
        return enforceWithinLimit(assignmentRepository.findByUserIdAndActiveTrue(userId, idPage()));
    }

    /**
     * Resolves a caller-controlled legacy department/group path to one active
     * canonical team. Missing and ambiguous targets are both deliberately
     * unresolved so authorization callers fail closed during the V36-to-V37
     * duplicate window.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolveUniqueActiveTeamId(String departmentName, String teamName) {
        if (departmentName == null || departmentName.isBlank()
                || teamName == null || teamName.isBlank()) {
            return Optional.empty();
        }
        Optional<Department> department = departmentRepository.findByName(departmentName)
                .filter(Department::isActive);
        if (department.isEmpty()) {
            return Optional.empty();
        }
        List<Team> matches = teamRepository.findByDepartmentIdAndNameAndActiveTrue(
                department.get().getId(), teamName,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "id")));
        return matches.size() == 1 ? Optional.of(matches.getFirst().getId()) : Optional.empty();
    }

    private static Pageable idPage() {
        return PageRequest.of(0, MAX_ROWS + 1, Sort.by(Sort.Direction.ASC, "id"));
    }

    private static Pageable namePage() {
        return PageRequest.of(0, MAX_ROWS + 1,
                Sort.by(Sort.Direction.ASC, "name").and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private static <T> List<T> enforceWithinLimit(List<T> rows) {
        if (rows.size() > MAX_ROWS) {
            throw new OrgDirectoryCardinalityExceededException();
        }
        return rows;
    }

    public static class OrgDirectoryCardinalityExceededException extends RuntimeException {
        public OrgDirectoryCardinalityExceededException() {
            super("Organization reference data exceeds the bounded complete-result response");
        }
    }
}
