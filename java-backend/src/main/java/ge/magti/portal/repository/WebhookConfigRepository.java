package ge.magti.portal.repository;

import ge.magti.portal.domain.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {
}
