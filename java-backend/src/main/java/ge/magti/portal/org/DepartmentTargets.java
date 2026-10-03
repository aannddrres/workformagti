package ge.magti.portal.org;

import ge.magti.portal.domain.Department;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.DepartmentMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a content audience names a department that exists.
 *
 * <p>Audiences are free text, matched against each employee's department
 * (prefix-aware, {@link DepartmentMatcher}), so nothing stopped a typo: an
 * article published and made mandatory for "ტექნიკურ" reached nobody and
 * reported success -- the obligation silently bound no one (simulation,
 * 2026-10-01).
 *
 * <p>Known means: {@code "All"}, a catalog department, or a department string
 * some employee actually has, or its group prefix. Free text stays possible --
 * the directory decides department names -- but only text that reaches people.
 */
@Service
public class DepartmentTargets {

    private final DepartmentRepository departments;
    private final UserRepository users;

    public DepartmentTargets(DepartmentRepository departments, UserRepository users) {
        this.departments = departments;
        this.users = users;
    }

    /** The targets that match no department, in the order given. */
    public List<String> unknown(Collection<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        known.add(DepartmentMatcher.WILDCARD_TARGET);
        for (Department department : departments.findAll()) {
            known.add(department.getName());
        }
        for (Object[] row : users.countGroupedByDepartment()) {
            if (row[0] instanceof String department && !department.isBlank()) {
                known.add(department);
                known.add(DepartmentMatcher.splitGroup(department).prefix());
            }
        }
        return targets.stream().filter(target -> target == null || !known.contains(target))
                .map(target -> Objects.toString(target, "")).distinct().toList();
    }

    /** A 422 naming the unknown targets, or null when every one is known. */
    public ResponseEntity<Map<String, String>> refusal(Collection<String> targets) {
        List<String> unknown = unknown(targets);
        if (unknown.isEmpty()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail",
                "ასეთი დეპარტამენტი არ არსებობს, მასალა არავის მიუვა: " + String.join(", ", unknown)));
    }
}
