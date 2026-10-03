package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Georgian display labels for audit action codes (e.g. "LOGIN" -&gt;
 * "შესვლა").
 */
@Entity
@Table(name = "audit_action_translations",
        uniqueConstraints = @UniqueConstraint(name = "uq_audit_action_translations_action", columnNames = "action"))
public class AuditActionTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "label_ka", nullable = false, length = 200)
    private String labelKa;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getLabelKa() {
        return labelKa;
    }

    public void setLabelKa(String labelKa) {
        this.labelKa = labelKa;
    }
}
