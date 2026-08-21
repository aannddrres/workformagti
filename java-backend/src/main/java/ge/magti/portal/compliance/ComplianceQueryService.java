package ge.magti.portal.compliance;

import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.stats.ComplianceRecord;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The DB-query half of compute_compliance (routers/stats.py:286-338) --
 * the single source of truth for the compliance numerator/denominator that
 * {@link ComplianceCalculator} (the pure formula) and every Stats view sit
 * on top of. Python keeps this deliberately as one function so the
 * dashboard, the org-wide summary, and the exports can never disagree on
 * "who counts" or "what's owed"; this service is the Java equivalent, and
 * (unlike Python, see {@link ComplianceCalculator}'s javadoc) it is the
 * only implementation on this side.
 *
 * <p>Enforces the one eligibility rule (active operators, management roles
 * excluded -- {@link ComplianceCalculator#isEligible}) and the one
 * required/read pairing rule ({@link ComplianceCalculator#computeProgress})
 * that every caller must use.
 */
@Service
public class ComplianceQueryService {

    private final UserRepository userRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;

    /** Phase 3 shadow only; nullable so the DB-free callers stay unchanged. */
    private final ComplianceEligibilityService eligibilityService;

    public ComplianceQueryService(
            UserRepository userRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository) {
        this(userRepository, requiredReadingRepository, readStatusRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ComplianceQueryService(
            UserRepository userRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ComplianceEligibilityService eligibilityService) {
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.eligibilityService = eligibilityService;
    }

    /** Whole-organisation compliance (every eligible operator). */
    public List<ComplianceRecord> computeCompliance() {
        return computeCompliance(null, null);
    }

    /** Scoped to a single user -- what get_my_progress uses. */
    public List<ComplianceRecord> computeForUser(Long userId) {
        return computeCompliance(List.of(userId), null);
    }

    /**
     * Port of compute_compliance(db, scope_user_ids, scope_department).
     *
     * @param scopeUserIds   when non-null, restrict to these user ids (on top of the
     *                       active/non-management base filter); may be empty
     * @param scopeDepartment when non-null, restrict to users whose department matches
     *                       exactly (Python uses {@code ==}, not prefix-aware, here)
     */
    public List<ComplianceRecord> computeCompliance(List<Long> scopeUserIds, String scopeDepartment) {
        List<User> candidates;
        if (scopeUserIds != null) {
            candidates = scopeUserIds.isEmpty() ? List.of() : userRepository.findAllById(scopeUserIds);
        } else if (scopeDepartment != null) {
            candidates = userRepository.findByActiveTrueAndDepartment(scopeDepartment);
        } else {
            candidates = userRepository.findByActiveTrue();
        }
        List<User> users = candidates.stream()
                .filter(candidate -> {
                    boolean legacy = ComplianceCalculator.isEligible(candidate);
                    // Phase 3: measures the override-and-leadership policy,
                    // serves the role-only rule. Flipping this rewrites every
                    // historical percentage at once, so it cuts over on the
                    // parity report rather than on a code review.
                    return eligibilityService == null
                            ? legacy
                            : eligibilityService.shadowCompare(candidate, legacy);
                })
                .toList();

        // SQL-side GROUP BY instead of hydrating every RequiredReading row.
        Map<String, Integer> requiredCountsByDept = new HashMap<>();
        for (Object[] row : requiredReadingRepository.countGroupedByTargetDepartment()) {
            requiredCountsByDept.put((String) row[0], ((Number) row[1]).intValue());
        }
        int allRequired = requiredCountsByDept.getOrDefault("All", 0);

        List<Long> userIds = users.stream().map(User::getId).toList();
        Map<ReadCountKey, Integer> readCountsByUserDept = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Object[] row : readStatusRepository.readCountsByUserAndDepartment(userIds)) {
                readCountsByUserDept.put(
                        new ReadCountKey(((Number) row[0]).longValue(), (String) row[1]),
                        ((Number) row[2]).intValue());
            }
        }

        return users.stream()
                .map(user -> new ComplianceRecord(user,
                        ComplianceCalculator.computeProgress(user, allRequired, requiredCountsByDept, readCountsByUserDept)))
                .toList();
    }
}
