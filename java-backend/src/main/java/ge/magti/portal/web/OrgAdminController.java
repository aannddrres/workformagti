package ge.magti.portal.web;

import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.LeadershipAssignmentService;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** SYSTEM_ADMIN-only UI surface over the expanded, AD-owned org model. */
@RestController
public class OrgAdminController {

    private final DepartmentRepository departmentRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final LeadershipAssignmentService assignmentService;
    private final OrgDirectoryQueryService orgDirectoryQueryService;

    public OrgAdminController(
            DepartmentRepository departmentRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            LeadershipAssignmentService assignmentService,
            OrgDirectoryQueryService orgDirectoryQueryService) {
        this.departmentRepository = departmentRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.assignmentService = assignmentService;
        this.orgDirectoryQueryService = orgDirectoryQueryService;
    }

    @GetMapping("/api/admin/org/structure")
    public ResponseEntity<?> getStructure(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }

        List<Department> departmentRows = orgDirectoryQueryService.listActiveDepartmentsWithinLimit();
        Set<Long> departmentIds = departmentRows.stream().map(Department::getId).collect(Collectors.toSet());
        List<Team> teamRows = orgDirectoryQueryService.listTeamsInDepartmentsWithinLimit(departmentIds);
        Map<Long, List<Team>> teamsByDepartment = teamRows.stream()
                .collect(Collectors.groupingBy(Team::getDepartmentId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, Long> memberCounts = new LinkedHashMap<>();
        List<Long> teamIds = teamRows.stream().map(Team::getId).toList();
        for (Object[] row : teamIds.isEmpty() ? List.<Object[]>of()
                : userRepository.countActiveGroupedByTeamIds(teamIds)) {
            memberCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        List<OrgDepartmentResponse> departments = departmentRows.stream()
                .map(department -> new OrgDepartmentResponse(
                        department.getId(), department.getStableKey(), department.getName(), department.isActive(),
                        teamsByDepartment.getOrDefault(department.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(Team::getName))
                                .map(team -> new OrgTeamResponse(
                                        team.getId(), team.getStableKey(), team.getName(), team.isActive(),
                                        memberCounts.getOrDefault(team.getId(), 0L)))
                                .toList()))
                .toList();
        return ResponseEntity.ok(new OrgStructureResponse(departments));
    }

    @GetMapping("/api/admin/org/assignments")
    public ResponseEntity<?> getAssignments(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(resolvedAssignments(orgDirectoryQueryService.listAssignmentsWithinLimit()));
    }

    @PostMapping("/api/admin/org/assignments")
    public ResponseEntity<?> createAssignment(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody LeadershipAssignmentRequest request) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        try {
            LeadershipAssignment saved = assignmentService.create(request, user);
            return ResponseEntity.ok(resolve(saved));
        } catch (LeadershipAssignmentService.AssignmentNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (LeadershipAssignmentService.InvalidAssignmentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (LeadershipAssignmentService.PrimaryConflictException | DataIntegrityViolationException e) {
            return error(HttpStatus.CONFLICT, "ამ scope-ზე აქტიური PRIMARY ლიდერი უკვე არსებობს");
        }
    }

    @DeleteMapping("/api/admin/org/assignments/{assignmentId}")
    public ResponseEntity<?> deactivateAssignment(
            @AuthenticationPrincipal User user,
            @PathVariable Long assignmentId) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        try {
            return ResponseEntity.ok(resolve(assignmentService.deactivate(assignmentId, user)));
        } catch (LeadershipAssignmentService.AssignmentNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private List<LeadershipAssignmentResponse> resolvedAssignments(List<LeadershipAssignment> assignments) {
        Map<Long, User> users = userRepository.findAllById(
                        assignments.stream().map(LeadershipAssignment::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Department> departments = departmentRepository.findAllById(
                        assignments.stream().map(LeadershipAssignment::getDepartmentId)
                                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        Map<Long, Team> teams = teamRepository.findAllById(
                        assignments.stream().map(LeadershipAssignment::getTeamId)
                                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        return assignments.stream()
                .sorted(Comparator.comparing(LeadershipAssignment::isActive).reversed()
                        .thenComparing(LeadershipAssignment::getStartedAt, Comparator.reverseOrder()))
                .map(assignment -> LeadershipAssignmentResponse.from(
                        assignment, users.get(assignment.getUserId()),
                        departments.get(assignment.getDepartmentId()), teams.get(assignment.getTeamId())))
                .toList();
    }

    private LeadershipAssignmentResponse resolve(LeadershipAssignment assignment) {
        User leader = userRepository.findById(assignment.getUserId()).orElseThrow();
        Department department = assignment.getDepartmentId() == null ? null
                : departmentRepository.findById(assignment.getDepartmentId()).orElse(null);
        Team team = assignment.getTeamId() == null ? null
                : teamRepository.findById(assignment.getTeamId()).orElse(null);
        return LeadershipAssignmentResponse.from(assignment, leader, department, team);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "Could not validate credentials");
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return error(HttpStatus.FORBIDDEN, "წვდომა უარყოფილია: არასაკმარისი უფლებები");
        }
        return null;
    }
}
