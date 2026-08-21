package ge.magti.portal.repository;

import ge.magti.portal.domain.LeadershipAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadershipAssignmentRepository extends JpaRepository<LeadershipAssignment, Long> {

    List<LeadershipAssignment> findByActiveTrue();

    List<LeadershipAssignment> findByUserIdAndActiveTrue(Long userId);

    List<LeadershipAssignment> findByTeamIdAndActiveTrue(Long teamId);

    List<LeadershipAssignment> findByDepartmentIdAndActiveTrue(Long departmentId);
}
