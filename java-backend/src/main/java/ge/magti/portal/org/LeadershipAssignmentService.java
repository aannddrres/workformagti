package ge.magti.portal.org;

import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.LeadershipAssignmentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional, audited lifecycle for manual leadership assignments. */
@Service
public class LeadershipAssignmentService {

    private final LeadershipAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final TeamRepository teamRepository;
    private final AuditLogRepository auditLogRepository;

    public LeadershipAssignmentService(
            LeadershipAssignmentRepository assignmentRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            TeamRepository teamRepository,
            AuditLogRepository auditLogRepository) {
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.teamRepository = teamRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public LeadershipAssignment create(LeadershipAssignmentRequest request, User actor) {
        if ((request.departmentId() == null) == (request.teamId() == null)) {
            throw new InvalidAssignmentException("ზუსტად ერთი scope უნდა აირჩეს: დეპარტამენტი ან ჯგუფი");
        }

        User leader = userRepository.findById(request.userId())
                .orElseThrow(() -> new AssignmentNotFoundException("მომხმარებელი ვერ მოიძებნა"));
        if (!leader.isActive()) {
            throw new InvalidAssignmentException("არააქტიური მომხმარებლის ლიდერად დანიშვნა არ შეიძლება");
        }

        Department department = null;
        Team team = null;
        if (request.departmentId() != null) {
            department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new AssignmentNotFoundException("დეპარტამენტი ვერ მოიძებნა"));
            if (!department.isActive()) {
                throw new InvalidAssignmentException("არააქტიურ დეპარტამენტზე დანიშვნა არ შეიძლება");
            }
        } else {
            team = teamRepository.findById(request.teamId())
                    .orElseThrow(() -> new AssignmentNotFoundException("ჯგუფი ვერ მოიძებნა"));
            if (!team.isActive()) {
                throw new InvalidAssignmentException("არააქტიურ ჯგუფზე დანიშვნა არ შეიძლება");
            }
        }

        rejectDuplicatePrimary(request);

        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(leader.getId());
        assignment.setDepartmentId(department == null ? null : department.getId());
        assignment.setTeamId(team == null ? null : team.getId());
        assignment.setAssignmentType(request.assignmentType());
        assignment.setActive(true);
        assignment.setStartedAt(TbilisiTime.now());
        assignment.setCreatedBy(actor.getId());
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        LeadershipAssignment saved = assignmentRepository.saveAndFlush(assignment);

        writeAudit(saved, actor, "CREATE_LEADERSHIP_ASSIGNMENT", leader, department, team);
        return saved;
    }

    @Transactional
    public LeadershipAssignment deactivate(Long assignmentId, User actor) {
        LeadershipAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new AssignmentNotFoundException("დანიშვნა ვერ მოიძებნა"));
        if (!assignment.isActive()) {
            return assignment;
        }

        User leader = userRepository.findById(assignment.getUserId())
                .orElseThrow(() -> new AssignmentNotFoundException("დანიშნული მომხმარებელი ვერ მოიძებნა"));
        Department department = assignment.getDepartmentId() == null ? null
                : departmentRepository.findById(assignment.getDepartmentId()).orElse(null);
        Team team = assignment.getTeamId() == null ? null
                : teamRepository.findById(assignment.getTeamId()).orElse(null);

        assignment.setActive(false);
        assignment.setEndedAt(TbilisiTime.now());
        LeadershipAssignment saved = assignmentRepository.saveAndFlush(assignment);
        writeAudit(saved, actor, "DEACTIVATE_LEADERSHIP_ASSIGNMENT", leader, department, team);
        return saved;
    }

    private void rejectDuplicatePrimary(LeadershipAssignmentRequest request) {
        if (request.assignmentType() != AssignmentType.PRIMARY) {
            return;
        }
        boolean exists = request.teamId() != null
                ? assignmentRepository.existsByTeamIdAndAssignmentTypeAndActiveTrue(
                        request.teamId(), AssignmentType.PRIMARY)
                : assignmentRepository.existsByDepartmentIdAndAssignmentTypeAndActiveTrue(
                        request.departmentId(), AssignmentType.PRIMARY);
        if (exists) {
            throw new PrimaryConflictException("ამ scope-ზე აქტიური PRIMARY ლიდერი უკვე არსებობს");
        }
    }

    private void writeAudit(
            LeadershipAssignment assignment, User actor, String action, User leader,
            Department department, Team team) {
        String scopeName = team == null ? department.getName() : team.getName();
        AuditLog audit = new AuditLog();
        audit.setAdminId(actor.getId());
        audit.setAction(action);
        audit.setItemType("leadership_assignment");
        audit.setItemId(assignment.getId());
        audit.setTimestamp(TbilisiTime.now());
        audit.setAdminNameSnapshot(actor.getName());
        audit.setAdminEmailSnapshot(actor.getEmail());
        audit.setItemNameSnapshot(leader.getName() + " — " + scopeName);
        audit.setDetails("type=" + assignment.getAssignmentType().name()
                + ", scope=" + assignment.scope().name() + ", source=" + assignment.getSource().name());
        auditLogRepository.save(audit);
    }

    public static class InvalidAssignmentException extends RuntimeException {
        public InvalidAssignmentException(String message) {
            super(message);
        }
    }

    public static class AssignmentNotFoundException extends RuntimeException {
        public AssignmentNotFoundException(String message) {
            super(message);
        }
    }

    public static class PrimaryConflictException extends RuntimeException {
        public PrimaryConflictException(String message) {
            super(message);
        }
    }
}
