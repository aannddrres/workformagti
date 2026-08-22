package ge.magti.portal.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutPropertiesTest {

    @Test
    void anUnconfiguredDeploymentKeepsBothCutoversOff() {
        PortalProperties properties = new PortalProperties();

        assertFalse(properties.getRollout().isLeadershipScopeEnabled());
        assertFalse(properties.getRollout().isComplianceEligibilityEnabled());
    }

    @Test
    void theTwoSwitchesAreIndependent() {
        PortalProperties properties = new PortalProperties();

        properties.getRollout().setLeadershipScopeEnabled(true);
        assertTrue(properties.getRollout().isLeadershipScopeEnabled());
        assertFalse(properties.getRollout().isComplianceEligibilityEnabled());

        properties.getRollout().setLeadershipScopeEnabled(false);
        properties.getRollout().setComplianceEligibilityEnabled(true);
        assertFalse(properties.getRollout().isLeadershipScopeEnabled());
        assertTrue(properties.getRollout().isComplianceEligibilityEnabled());
    }

    @Test
    void applicationYamlMapsBothEnvironmentVariablesWithFalseDefaults() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(yaml.contains(
                "leadership-scope-enabled: ${ROLLOUT_LEADERSHIP_SCOPE:false}"));
        assertTrue(yaml.contains(
                "compliance-eligibility-enabled: ${ROLLOUT_COMPLIANCE_ELIGIBILITY:false}"));
    }
}
