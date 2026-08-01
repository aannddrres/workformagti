package ge.magti.portal.repository;

import ge.magti.portal.domain.AuditActionTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditActionTranslationRepository extends JpaRepository<AuditActionTranslation, Long> {
}
