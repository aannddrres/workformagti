package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The configuration production actually runs on (DEC-P01, shadow).
 *
 * <p>Shadow is not "the feature switched off" -- it is a mode with behaviour
 * of its own, and it is the only one anyone observes during the rollout
 * window. If it silently stopped recording, the promotion decision would be
 * taken on an empty table and read as "nothing was ever denied".
 *
 * <p>So both halves are pinned: the file is still served, <b>and</b> the
 * intent to refuse is written down.
 *
 * @see FileEntitlementEnforcedIntegrationTest for the same scenario with the
 *      switch on
 */
@RequiresOracle
@SpringBootTest(properties = "portal.rollout.file-entitlement-enabled=false")
@AutoConfigureMockMvc
@Transactional
class FileEntitlementShadowIntegrationTest extends FileEntitlementScenarioSupport {

    @Test
    void anOutsiderStillGetsTheFileButTheRefusalIsRecorded() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.outsiderToken()))
                .andExpect(status().isOk());

        var recorded = decisionFor(fixture.outsider(), "FILE_ACCESS_SHADOW_DENY")
                .orElseThrow(() -> new AssertionError(
                        "shadow must record the would-be refusal, or the promotion criterion "
                                + "reads an empty table as 'nothing was ever denied'"));
        assertEquals("SHADOW", recorded.get("result").asText());
        assertEquals("DENIED_NOT_VISIBLE", recorded.get("reason").asText());
        assertEquals(fixture.filename(), recorded.at("/after/stored_filename").asText());
        assertEquals(fixture.outsiderDepartment(), recorded.at("/after/department").asText());

        assertTrue(decisionFor(fixture.outsider(), "FILE_ACCESS_DENIED").isEmpty(),
                "shadow must never write the enforced action -- 'would have' and 'did' stay apart");
    }

    @Test
    void theIntendedAudienceIsNotRecordedAtAll() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.insiderToken()))
                .andExpect(status().isOk());

        // A shadow row for someone who may read the article would be a false
        // positive in exactly the table the promotion criterion counts.
        assertTrue(decisionFor(fixture.insider(), "FILE_ACCESS_SHADOW_DENY").isEmpty(),
                "a legitimate reader must produce no shadow row");
    }
}
