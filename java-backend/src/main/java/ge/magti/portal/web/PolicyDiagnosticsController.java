package ge.magti.portal.web;

import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.OrgBackfillPlan;
import ge.magti.portal.org.OrgBackfillService;
import ge.magti.portal.org.OrgSchemaPreflight;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.security.PolicyShadowRecorder;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The readout for the Phase 3 shadow run and the Phase 2 backfill.
 *
 * <p>Both of those produce the evidence the plan makes the Phase 4 cutover
 * conditional on -- and until now neither could be read from anywhere. A
 * measurement nobody can see is not a measurement, and "the reconciliation
 * report must be clean before cutover" is not a gate if the only way to
 * consult it is to grep application logs.
 *
 * <p>SYSTEM_ADMIN only, all four. The shadow snapshot carries no personal
 * data -- counts per decision point, nothing else -- but the backfill report
 * names the users it could not place, which makes it employee data under the
 * same rule as everything else here, and the schema preflight names groups.
 *
 * <p>The two gates read differently and are meant to. The backfill report's
 * {@code blocks_cutover} is about people: rows nobody has decided on yet. The
 * schema preflight's {@code blocks_v37} is about the table: rows a constraint
 * would reject. Both must be false before the contract migration runs, and
 * neither implies the other.
 */
@RestController
public class PolicyDiagnosticsController {

    private final PolicyShadowRecorder shadowRecorder;
    private final OrgBackfillService orgBackfillService;
    private final OrgSchemaPreflight orgSchemaPreflight;
    private final AuditLogRepository auditLogRepository;

    public PolicyDiagnosticsController(
            PolicyShadowRecorder shadowRecorder,
            OrgBackfillService orgBackfillService,
            OrgSchemaPreflight orgSchemaPreflight,
            AuditLogRepository auditLogRepository) {
        this.shadowRecorder = shadowRecorder;
        this.orgBackfillService = orgBackfillService;
        this.orgSchemaPreflight = orgSchemaPreflight;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * How far apart the rules in force and the Phase 3 policy layer are, per
     * decision point.
     *
     * <p>Counters are per process and reset on restart. That is deliberate
     * rather than a limitation to fix later: this answers "what is the shape of
     * the difference right now", which is a question about the running
     * deployment, and persisting it would invite treating a stale total as
     * evidence that the cutover is safe.
     *
     * <p>Read the {@code unexercised} flag before the numbers. A decision point
     * with zero of both has not been reached at all, which is not the same as
     * agreeing -- and is the easiest way to conclude a cutover is safe when
     * nothing has actually been tested.
     */
    @GetMapping("/api/admin/policy-shadow")
    public ResponseEntity<?> getPolicyShadow(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }

        Map<String, Object> decisions = new LinkedHashMap<>();
        shadowRecorder.snapshot().forEach((decision, counts) -> decisions.put(decision, Map.of(
                "agreed", counts.agreed(),
                "disagreed", counts.disagreed(),
                "unexercised", counts.unexercised())));

        return ResponseEntity.ok(Map.of(
                "decisions", decisions,
                "generated_at", TbilisiTime.now()));
    }

    /**
     * What the org backfill would do, without doing it.
     *
     * <p>{@code blocks_cutover} is the plan's §8 gate in one field: while any
     * row needs a decision, neither V37 nor the Phase 4 scope cutover may go
     * ahead. Fail-closed scoping only behaves well if somebody has looked at
     * the list of people it is about to close on.
     */
    @GetMapping("/api/admin/org-backfill/report")
    public ResponseEntity<?> getBackfillReport(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(describe(orgBackfillService.plan()));
    }

    /**
     * Runs the backfill.
     *
     * <p>{@link OrgBackfillService}'s javadoc used to say there would be no
     * endpoint for this, on the grounds that it is an operator action rather
     * than a button. That was the wrong conclusion from a right premise: the
     * work still has to be triggered by somebody, and an audited call only a
     * system admin can make is exactly what "an operator action" means here.
     * Phase 8 puts a screen in front of this; it does not change who may do it
     * or what gets recorded.
     *
     * <p>Idempotent, so running it twice is not a mistake -- which matters,
     * because getting the reconciliation list to empty takes more than one
     * pass.
     */
    @PostMapping("/api/admin/org-backfill/apply")
    public ResponseEntity<?> applyBackfill(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }

        OrgBackfillPlan plan = orgBackfillService.apply(user.getId());

        AuditLog audit = new AuditLog();
        audit.setAdminId(user.getId());
        audit.setAction("ORG_BACKFILL_APPLY");
        audit.setItemType("org_structure");
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        return ResponseEntity.ok(describe(plan));
    }

    /**
     * Whether V37 would apply against this database, or fail part-way.
     *
     * <p>The migration itself is not written yet and must not be until the
     * backfill has run cleanly in production. This is what tells somebody
     * whether that day has come: {@code blocks_v37} false, plus
     * {@code blocks_cutover} false from the report above.
     *
     * <p>A finding with {@code blocking: false} is not noise to skip. It is a
     * constraint V36 already created reporting a row it should have made
     * impossible, which means the constraint is not on this schema -- a
     * different and worse problem than the ones V37 is about.
     */
    @GetMapping("/api/admin/org-schema-preflight")
    public ResponseEntity<?> getSchemaPreflight(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(user);
        if (denial != null) {
            return denial;
        }

        OrgSchemaPreflight.Report report = orgSchemaPreflight.run();
        List<Map<String, Object>> findings = report.violations().stream()
                .map(violation -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("constraint", violation.constraint());
                    row.put("blocking", violation.blocking());
                    row.put("rows", violation.rows());
                    row.put("detail", violation.detail());
                    row.put("samples", violation.samples());
                    return row;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blocks_v37", report.blocksV37());
        body.put("clean", report.clean());
        body.put("findings", findings);
        body.put("generated_at", TbilisiTime.now());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> describe(OrgBackfillPlan plan) {
        List<Map<String, Object>> issues = plan.issues().stream()
                .map(issue -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", issue.kind().name());
                    row.put("user_id", issue.userId());
                    row.put("subject", issue.subject());
                    row.put("detail", issue.detail());
                    return row;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups_to_create", plan.teamsToCreate().size());
        body.put("memberships", plan.memberships().size());
        body.put("leaders_resolved", plan.leadership().size());
        body.put("needs_a_decision", plan.issues().size());
        body.put("blocks_cutover", plan.hasUnresolvedIssues());
        body.put("issues", issues);
        body.put("report", OrgBackfillService.report(plan));
        body.put("generated_at", TbilisiTime.now());
        return body;
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }
}
