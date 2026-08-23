package ge.magti.portal.compliance;

import ge.magti.portal.domain.User;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.security.PolicyShadowRecorder;
import org.springframework.stereotype.Service;

/**
 * Who is subject to mandatory reading (plan §5.6).
 *
 * <h2>The rule, in precedence order</h2>
 *
 * <ol>
 *   <li><b>Inactive</b> -- never eligible.
 *   <li><b>A system admin's explicit override</b> ({@code users.compliance_override})
 *       decides, in either direction. This is the control plan §2 requires:
 *       "operator + content permission default-ად რჩება compliance-ში, ხოლო
 *       სისტემურ ადმინს შეუძლია ეს სტატუსი შეცვალოს".
 *   <li><b>A management role</b> -- manager, content admin, system admin -- is
 *       out. Unchanged from {@link ComplianceCalculator#MANAGEMENT_ROLES}.
 *   <li><b>An active leadership assignment</b> is out, whatever the role says.
 *       Plan §2: a group leader is not a participant in mandatory reading.
 *   <li>Otherwise in.
 * </ol>
 *
 * <h2>The case this exists for</h2>
 *
 * An operator who is granted content permissions <b>stays in compliance</b>.
 * Today's rule cannot express that, because it reads the role and a content
 * permission has to come with the CONTENT_ADMIN role to have any effect. Once
 * permissions are per-user (Phase 6), "operator who can also publish" is an
 * ordinary operator with an extra ability -- and still someone whose reading is
 * audited, which is the whole point of the compliance record.
 *
 * <p>Note the order of 3 and 4: both exclude, so it never changes an outcome,
 * but it does change the reason. Once leadership is an assignment rather than a
 * role, a full-time content admin is excluded for being a content admin and an
 * acting group leader is excluded for leading a group -- and the eligibility
 * transition audit (plan §4.2) has to record which.
 *
 * <h2>Why changing this is the riskiest step in the plan</h2>
 *
 * Compliance percentages are computed at query time, so flipping eligibility
 * rewrites every historical percentage in the same instant -- and those records
 * are usable as evidence about what an operator knew (PRODUCT_UX §1). Hence
 * shadow mode, and hence the parity report being a gate on Phase 5 rather than
 * a follow-up to it.
 */
@Service
public class ComplianceEligibilityService {

    /** Matches {@code ComplianceCalculator.isEligible}'s decision point. */
    public static final String DECISION = "compliance.eligibility";

    private final LeadershipAssignmentRepository leadershipAssignmentRepository;
    private final PolicyShadowRecorder shadowRecorder;

    public ComplianceEligibilityService(
            LeadershipAssignmentRepository leadershipAssignmentRepository, PolicyShadowRecorder shadowRecorder) {
        this.leadershipAssignmentRepository = leadershipAssignmentRepository;
        this.shadowRecorder = shadowRecorder;
    }

    public boolean isEligible(User user) {
        if (user == null || !user.isActive()) {
            return false;
        }
        return resolve(user, hasActiveLeadership(user));
    }

    private boolean hasActiveLeadership(User user) {
        return !leadershipAssignmentRepository.findByUserIdAndActiveTrue(user.getId()).isEmpty();
    }

    /**
     * The rule itself, DB-free -- see the class javadoc for the precedence and
     * why it is in that order.
     *
     * <p>Public because it is the canonical statement of the policy, and a
     * caller that already knows whether this person leads anything should be
     * able to ask without a second query. It is also what the parity report
     * evaluates, and a rule that can only be reached through a Spring bean is
     * a rule that is hard to compare against the old one.
     */
    public static boolean resolve(User user, boolean hasActiveLeadership) {
        if (user == null || !user.isActive()) {
            return false;
        }
        Boolean override = user.getComplianceOverride();
        if (override != null) {
            return override;
        }
        if (ComplianceCalculator.MANAGEMENT_ROLES.contains(user.getRole())) {
            return false;
        }
        return !hasActiveLeadership;
    }

    /**
     * Records what this service would answer and returns the legacy answer
     * unchanged -- see {@code ScopeResolver.decide} for the reasoning
     * behind the return value.
     */
    public boolean shadowCompare(User user, boolean legacy) {
        try {
            shadowRecorder.record(DECISION, user == null ? null : user.getId(), legacy, isEligible(user));
        } catch (RuntimeException e) {
            shadowRecorder.record(DECISION + ".error", user == null ? null : user.getId(),
                    "ok", e.getClass().getSimpleName());
        }
        return legacy;
    }

    /** Only ever the role part, for callers that must not touch the database (batch paths). */
    public static boolean isEligibleByRoleOnly(User user) {
        return user != null && user.isActive()
                && !ComplianceCalculator.MANAGEMENT_ROLES.contains(user.getRole());
    }
}
