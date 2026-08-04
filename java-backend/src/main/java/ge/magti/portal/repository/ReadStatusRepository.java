package ge.magti.portal.repository;

import ge.magti.portal.domain.ReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReadStatusRepository extends JpaRepository<ReadStatus, Long> {

    Optional<ReadStatus> findByUserIdAndRequiredReadingId(Long userId, Long requiredReadingId);
}
