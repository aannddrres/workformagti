package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A webhook's configuration. {@link
 * #triggerActions} is a comma-separated list of action names (e.g.
 * "LOGIN_FAILED,UPDATE_PERMISSIONS"), not a normalized relation.
 */
@Entity
@Table(name = "webhook_configs")
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "trigger_actions", length = 500)
    private String triggerActions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getTriggerActions() {
        return triggerActions;
    }

    public void setTriggerActions(String triggerActions) {
        this.triggerActions = triggerActions;
    }
}
