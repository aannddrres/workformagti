package ge.magti.portal.repository;

import ge.magti.portal.domain.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportJobRepository extends JpaRepository<ExportJob, String> {
}
