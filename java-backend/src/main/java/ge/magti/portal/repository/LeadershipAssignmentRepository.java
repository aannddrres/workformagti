package ge.magti.portal.repository;

import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.LeadershipAssignment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadershipAssignmentRepository extends JpaRepository<LeadershipAssignment, Long> {

    List<LeadershipAssignment> findByActiveTrue();

    List<LeadershipAssignment> findByActiveTrue(Pageable pageable);

    List<LeadershipAssignment> findByUserIdAndActiveTrue(Long userId);

    List<LeadershipAssignment> findByUserIdAndActiveTrue(Long userId, Pageable pageable);

    boolean existsByUserIdAndActiveTrue(Long userId);

    boolean existsByUserIdAndTeamIdIsNotNullAndActiveTrue(Long userId);

    boolean existsByUserIdAndTeamIdAndActiveTrue(Long userId, Long teamId);

    List<LeadershipAssignment> findByTeamIdAndActiveTrue(Long teamId);

    List<LeadershipAssignment> findByDepartmentIdAndActiveTrue(Long departmentId);

    boolean existsByTeamIdAndAssignmentTypeAndActiveTrue(Long teamId, AssignmentType assignmentType);

    boolean existsByDepartmentIdAndAssignmentTypeAndActiveTrue(Long departmentId, AssignmentType assignmentType);
}
