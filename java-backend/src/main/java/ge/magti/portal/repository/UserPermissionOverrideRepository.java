package ge.magti.portal.repository;

import ge.magti.portal.domain.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPermissionOverrideRepository extends JpaRepository<UserPermissionOverride, Long> {

    List<UserPermissionOverride> findByUserId(Long userId);

    List<UserPermissionOverride> findByUserIdIn(List<Long> userIds);

    void deleteByUserIdAndPermission(Long userId, String permission);
}
