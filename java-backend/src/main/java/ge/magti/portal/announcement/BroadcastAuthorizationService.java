package ge.magti.portal.announcement;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.security.PermissionChecker;
import org.springframework.stereotype.Service;

/** One server-side answer to who may publish a company-wide announcement. */
@Service
public class BroadcastAuthorizationService {

    private final PermissionChecker permissionChecker;
    private final LeadershipAssignmentRepository leadershipRepository;

    public BroadcastAuthorizationService(
            PermissionChecker permissionChecker,
            LeadershipAssignmentRepository leadershipRepository) {
        this.permissionChecker = permissionChecker;
        this.leadershipRepository = leadershipRepository;
    }

    public boolean canPublish(User user) {
        if (user == null || !user.isActive()) {
            return false;
        }
        if (user.getRole() == Role.SYSTEM_ADMIN || permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE)) {
            return true;
        }
        // Product rule says group leader. A department leadership assignment
        // intentionally does not widen this capability.
        return leadershipRepository.findByUserIdAndActiveTrue(user.getId()).stream()
                .anyMatch(assignment -> assignment.getTeamId() != null);
    }
}
