package ge.magti.portal.compliance;

import ge.magti.portal.compliance.ComplianceAggregateRepository.AggregateRow;
import ge.magti.portal.compliance.ComplianceAggregateRepository.ScopeTarget;
import ge.magti.portal.domain.User;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.DepartmentMatcher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Complete, bounded query interface for user compliance progress.
 *
 * <p>Callers provide no more than the existing 1,000-user directory slice.
 * This service derives the established exact/prefix/All target set in Java,
 * asks Oracle for one row per relevant pair, validates the result shape, and
 * delegates the business formula and rounding to {@link ComplianceCalculator}.
 * Only readings in force count (PO-40, {@link MandatoryReach}): an operator is
 * never short of a percentage for material they could not open.
 */
@Service
public class ComplianceProgressQueryService {

    static final int MAX_TARGETS_PER_USER = 3;

    private final ComplianceAggregateRepository aggregateRepository;
    private final MandatoryReach mandatoryReach;

    public ComplianceProgressQueryService(
            ComplianceAggregateRepository aggregateRepository, MandatoryReach mandatoryReach) {
        this.aggregateRepository = aggregateRepository;
        this.mandatoryReach = mandatoryReach;
    }

    public Map<Long, ReadingProgress> progressByUser(List<User> users) {
        if (users.size() > UserDirectoryQueryService.MAX_ACTIVE_USERS) {
            throw new UserDirectoryQueryService.ActiveUserCardinalityExceededException();
        }
        if (users.isEmpty()) {
            return Map.of();
        }

        List<ScopeTarget> scopes = scopeTargetsFor(users);
        Set<Long> inForce = mandatoryReach.inForceIdsForTargets(
                scopes.stream().map(ScopeTarget::targetDepartment).toList());
        List<AggregateRow> rows = aggregateRepository.findRelevantCounts(scopes, inForce);
        if (rows.size() != scopes.size()) {
            throw new ComplianceAggregateShapeException();
        }

        Map<String, Integer> requiredCountsByTarget = new LinkedHashMap<>();
        Map<ReadCountKey, Integer> readCountsByUserTarget = new LinkedHashMap<>();
        Set<ScopeTarget> expected = new LinkedHashSet<>(scopes);
        for (AggregateRow row : rows) {
            ScopeTarget actual = new ScopeTarget(row.userId(), row.targetDepartment());
            if (!expected.remove(actual)) {
                throw new ComplianceAggregateShapeException();
            }
            Integer existingRequired = requiredCountsByTarget.putIfAbsent(
                    row.targetDepartment(), row.requiredCount());
            if (existingRequired != null && existingRequired != row.requiredCount()) {
                throw new ComplianceAggregateShapeException();
            }
            readCountsByUserTarget.put(
                    new ReadCountKey(row.userId(), row.targetDepartment()), row.readCount());
        }
        if (!expected.isEmpty()) {
            throw new ComplianceAggregateShapeException();
        }

        int allRequired = requiredCountsByTarget.getOrDefault("All", 0);
        Map<Long, ReadingProgress> result = new LinkedHashMap<>();
        for (User user : users) {
            if (result.put(user.getId(), ComplianceCalculator.computeProgress(
                    user, allRequired, requiredCountsByTarget, readCountsByUserTarget)) != null) {
                throw new ComplianceAggregateShapeException();
            }
        }
        return Map.copyOf(result);
    }

    static List<ScopeTarget> scopeTargetsFor(List<User> users) {
        Set<ScopeTarget> scopes = new LinkedHashSet<>();
        for (User user : users) {
            scopes.add(new ScopeTarget(user.getId(), "All"));
            String department = user.getDepartment();
            if (department == null || "All".equals(department)) {
                continue;
            }
            if (!department.isEmpty()) {
                scopes.add(new ScopeTarget(user.getId(), department));
            }
            String prefix = DepartmentMatcher.splitGroup(department).prefix();
            if (!prefix.isEmpty()) {
                scopes.add(new ScopeTarget(user.getId(), prefix));
            }
        }
        if (scopes.size() > Math.multiplyExact(users.size(), MAX_TARGETS_PER_USER)) {
            throw new ComplianceAggregateShapeException();
        }
        return List.copyOf(scopes);
    }

    public static class ComplianceAggregateShapeException extends RuntimeException {
        public ComplianceAggregateShapeException() {
            super("Compliance aggregate query returned an unexpected result shape");
        }
    }
}
