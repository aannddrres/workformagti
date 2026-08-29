package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same scenario once {@code ROLLOUT_FILE_ENTITLEMENT} is on -- UAT finding
 * F-1, kept as a regression test.
 *
 * <p>The refusal is a 404 rather than a 403 on purpose: a 403 confirms the
 * file exists, which is the single fact someone guessing stored names is
 * trying to establish.
 */
@RequiresOracle
@SpringBootTest(properties = "portal.rollout.file-entitlement-enabled=true")
@AutoConfigureMockMvc
@Transactional
class FileEntitlementEnforcedIntegrationTest extends FileEntitlementScenarioSupport {

    @Test
    void anOutsiderIsRefusedAsThoughTheFileDidNotExist() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.outsiderToken()))
                .andExpect(status().isNotFound());

        var recorded = decisionFor(fixture.outsider(), "FILE_ACCESS_DENIED")
                .orElseThrow(() -> new AssertionError("an enforced refusal must be audited"));
        assertEquals("DENIED", recorded.get("result").asText());
        assertEquals("DENIED_NOT_VISIBLE", recorded.get("reason").asText());
    }

    @Test
    void theIntendedAudienceStillGetsTheFile() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.insiderToken()))
                .andExpect(status().isOk());
    }
}
