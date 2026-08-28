package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.OrgBackfillIssue;
import ge.magti.portal.org.OrgBackfillPlan;
import ge.magti.portal.org.OrgBackfillService;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.security.PolicyShadowRecorder;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The readout the Phase 4 cutover gate depends on.
 *
 * <p>Two things are worth asserting beyond the happy path. The gate itself has
 * to be a field rather than something a reader infers from a list length --
 * "the report was empty" and "nobody looked" produce the same impression
 * otherwise. And applying the backfill has to leave an audit row, because a
 * backfilled leader appearing with no record of who ran the job is the same
 * problem the leadership model exists to fix.
 */
class PolicyDiagnosticsControllerTest {

    private final PolicyShadowRecorder recorder = new PolicyShadowRecorder();
    private final OrgBackfillService backfill = mock(OrgBackfillService.class);
    private final MutationAuditService mutationAuditService = mock(MutationAuditService.class);
    private final PolicyDiagnosticsController controller =
            new PolicyDiagnosticsController(recorder, backfill, mutationAuditService);

    private static User of(Role role) {
        User user = new User();
        user.setId(1L);
        user.setRole(role);
        user.setActive(true);
        return user;
    }

    private static OrgBackfillPlan planWith(List<OrgBackfillIssue> issues) {
        return new OrgBackfillPlan(List.of(), List.of(), List.of(), issues);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return assertInstanceOf(Map.class, response.getBody());
    }

    @Test
    void allThreeAreSystemAdminOnly() {
        for (Role role : List.of(Role.MANAGER, Role.CONTENT_ADMIN, Role.OPERATOR)) {
            User caller = of(role);
            assertEquals(HttpStatus.FORBIDDEN, controller.getPolicyShadow(caller).getStatusCode(), role.value());
            assertEquals(HttpStatus.FORBIDDEN, controller.getBackfillReport(caller).getStatusCode(), role.value());
            assertEquals(HttpStatus.FORBIDDEN, controller.applyBackfill(caller).getStatusCode(), role.value());
        }
        assertEquals(HttpStatus.UNAUTHORIZED, controller.getPolicyShadow(null).getStatusCode());
    }

    @Test
    void theShadowSnapshotReportsBothCountsPerDecision() {
        recorder.record("scope.export", 7L, List.of(1L), List.of());
        recorder.record("scope.export", 8L, List.of(2L), List.of(2L));

        Map<String, Object> decisions = (Map<String, Object>) bodyOf(controller.getPolicyShadow(of(Role.SYSTEM_ADMIN)))
                .get("decisions");

        assertEquals(Map.of("agreed", 1L, "disagreed", 1L, "unexercised", false), decisions.get("scope.export"));
    }

    /**
     * The distinction the flag exists for: a decision point nothing reached
     * looks identical to a clean one if you only read the counts.
     */
    @Test
    void anUnreachedDecisionIsNotReportedAsClean() {
        assertTrue(new PolicyShadowRecorder.Counts(0, 0).unexercised());
        assertFalse(new PolicyShadowRecorder.Counts(5, 0).unexercised());
    }

    /** Plan §8's gate, as a field rather than something the reader has to derive. */
    @Test
    void anUnresolvedIssueIsReportedAsBlockingTheCutover() {
        when(backfill.plan()).thenReturn(planWith(List.of(new OrgBackfillIssue(
                OrgBackfillIssue.Kind.DEPARTMENT_ONLY_MANAGER, 42L, "ტექნიკური", "names a department but no group"))));

        Map<String, Object> body = bodyOf(controller.getBackfillReport(of(Role.SYSTEM_ADMIN)));

        assertEquals(true, body.get("blocks_cutover"));
        assertEquals(1, body.get("needs_a_decision"));
        assertTrue(String.valueOf(body.get("report")).contains("V37 must NOT be applied"));
    }

    @Test
    void aCleanReportSaysTheCutoverMayProceed() {
        when(backfill.plan()).thenReturn(planWith(List.of()));

        Map<String, Object> body = bodyOf(controller.getBackfillReport(of(Role.SYSTEM_ADMIN)));

        assertEquals(false, body.get("blocks_cutover"));
        assertTrue(String.valueOf(body.get("report")).contains("V37 may be applied"));
    }

    /** Reporting must not write anything -- it is the thing you run before deciding. */
    @Test
    void theReportIsADryRun() {
        when(backfill.plan()).thenReturn(planWith(List.of()));

        controller.getBackfillReport(of(Role.SYSTEM_ADMIN));

        verify(backfill, never()).apply(anyLong());
        verifyNoInteractions(mutationAuditService);
    }

    /** A backfilled leader with no record of who created it is the problem, not the fix. */
    @Test
    void applyingIsAudited() {
        when(backfill.apply(1L)).thenReturn(planWith(List.of()));

        assertEquals(HttpStatus.OK, controller.applyBackfill(of(Role.SYSTEM_ADMIN)).getStatusCode());

        verify(backfill).apply(1L);
        verify(mutationAuditService).recordSuccess(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void oversizedBackfillFailsLoudlyBeforeApplyAndAuditsTheRejectedMutation() {
        when(backfill.plan()).thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());
        when(backfill.apply(1L)).thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());
        User admin = of(Role.SYSTEM_ADMIN);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.getBackfillReport(admin).getStatusCode());
        ResponseEntity<?> applyResponse = controller.applyBackfill(admin);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, applyResponse.getStatusCode());
        assertTrue(String.valueOf(bodyOf(applyResponse).get("detail")).contains("უსაფრთხო ზღვარს"));

        verify(mutationAuditService).recordResult(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(mutationAuditService, never()).recordSuccess(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void oversizedOrgReferenceSnapshotAlsoFailsLoudlyAndAuditsTheRejectedMutation() {
        when(backfill.plan()).thenThrow(
                new OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException());
        when(backfill.apply(1L)).thenThrow(
                new OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException());
        User admin = of(Role.SYSTEM_ADMIN);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.getBackfillReport(admin).getStatusCode());
        ResponseEntity<?> applyResponse = controller.applyBackfill(admin);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, applyResponse.getStatusCode());
        assertTrue(String.valueOf(bodyOf(applyResponse).get("detail")).contains("უსაფრთხო ზღვარს"));

        verify(mutationAuditService).recordResult(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(mutationAuditService, never()).recordSuccess(
                any(), any(), any(), any(), any(), any(), any());
    }
}
